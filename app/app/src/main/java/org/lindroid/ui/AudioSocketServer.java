package org.lindroid.ui;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AudioSocketServer {
    private static final byte[] AUDIO_OUTPUT_PREFIX = new byte[] { 0x01 };
    private static final byte AUDIO_INPUT_PREFIX = 0x02;

    private static final String TAG = "AudioSocketServer";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LocalServerSocket serverSocket;
    private boolean isRunning = false;

    private AudioTrack audioTrack;
    private AudioRecord audioRecord;

    public AudioSocketServer() {
        executor.execute(() -> {
            try {
                // Remove existing socket file if it exists
                File socketFile = new File(Constants.SOCKET_PATH);
                if (socketFile.exists()) {
                    if (!socketFile.delete()) {
                        Log.w(TAG, "failed to delete socket");
                    }
                }

                try {
                    // Create a UNIX domain socket file descriptor
                    FileDescriptor socketFd = Os.socket(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0);

                    // Bind the socket to the file path
                    Os.bind(socketFd, Constants.createUnixSocketAddressObj(Constants.SOCKET_PATH));

                    // Set the socket to listen for incoming connections
                    Os.listen(socketFd, 50);

                    serverSocket = new LocalServerSocket(socketFd);
                    isRunning = true;
                    Log.i(TAG, "Server started at " + Constants.SOCKET_PATH);

                    // Initialize AudioTrack and AudioRecord
                    initializeAudioTrack();
                    initializeAudioRecord();

                    while (isRunning) {
                        LocalSocket clientSocket = serverSocket.accept();
                        Log.i(TAG, "Accepted client");
                        handleClient(clientSocket);
                        sendMicDataToSocket(clientSocket.getOutputStream());
                    }
                } catch (ErrnoException e) {
                    Log.e(TAG, "Error setting up server socket", e);
                }
            } catch (IOException e) {
                Log.e(TAG, "Error starting server", e);
            }
        });
    }

    private void sendMicDataToSocket(OutputStream outputStream) {
        executor.execute(() -> {
            byte[] buffer = new byte[10240];

            try {
                while (isRunning) {
                    // Reset the buffer before reading
                    Arrays.fill(buffer, (byte) 0);

                    // Add the prefix at the start of the buffer
                    buffer[0] = AUDIO_INPUT_PREFIX;

                    // Read data from the microphone
                    int bytesRead = audioRecord.read(buffer, 1, buffer.length - 1);

                    // Only write if we have actually read data
                    if (bytesRead > 0) {
                        // Add 1 to bytesRead to include the prefix in the output
                        outputStream.write(buffer, 0, bytesRead + 1);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error sending microphone data", e);
            }
        });
    }


    private void initializeAudioTrack() {
        // Assuming audio is PCM 16-bit, 48000Hz, stereo
        int sampleRate = 48000;
        int channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

        int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        audioTrack = new AudioTrack(
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                        .setUsage(AudioAttributes.USAGE_UNKNOWN)
                        .setIsContentSpatialized(true)
                        .build(),
                new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .build(),
                minBufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
        );

        audioTrack.play();
    }

    @SuppressLint("MissingPermission") // we're system
    private void initializeAudioRecord() {
        int sampleRate = 48000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize
        );

        audioRecord.startRecording();
    }

    private void handleClient(LocalSocket clientSocket) {
        executor.execute(() -> {
            try (InputStream inputStream = clientSocket.getInputStream()) {
                byte[] buffer = new byte[10240];
                int bytesRead;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    if (bytesRead > 1) {
                        byte prefix = buffer[0];
                        if (prefix == AUDIO_OUTPUT_PREFIX[0]) {
                            audioTrack.write(buffer, 1, bytesRead - 1);
                            Log.i(TAG, "Received audio output data: " + (bytesRead - 1) + " bytes");
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error handling client", e);
            }
        });
    }

    public void stopServer() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(100, TimeUnit.MILLISECONDS))
                executor.shutdownNow();
        } catch (InterruptedException e) {
            throw new RuntimeException(e); // who dares interrupt the main thread?
        }
        isRunning = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server", e);
            }
        }
        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
        }
    }
}
