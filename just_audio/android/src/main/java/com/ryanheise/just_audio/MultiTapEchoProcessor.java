package com.ryanheise.just_audio;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MultiTapEchoProcessor implements AudioProcessor {
  /** The current input audio format. */
  private AudioFormat inputAudioFormat;

  /** The current output audio format. */
  private AudioFormat outputAudioFormat;

  private AudioFormat pendingInputAudioFormat;
  private AudioFormat pendingOutputAudioFormat;
  private ByteBuffer buffer;
  private ByteBuffer outputBuffer;
  private boolean inputEnded;

  // FFmpeg parameters
  private final float inputGain = 0.8f;
  private final float outputGain = 0.7f;
  private final int[] delaysMs = {60, 120, 180};
  private final float[] decays = {0.4f, 0.3f, 0.2f};

  // Delay line state
  private short[] delayLine; // Circular buffer
  private int[] tapSampleOffsets;
  private int writeIndex = 0;

  private volatile boolean isEnabled = true;  // Use volatile for thread safety
  private volatile boolean wasEnabled = true; // Use volatile for thread safety

  public MultiTapEchoProcessor() {
    buffer = EMPTY_BUFFER;
    outputBuffer = EMPTY_BUFFER;
    pendingInputAudioFormat = AudioFormat.NOT_SET;
    pendingOutputAudioFormat = AudioFormat.NOT_SET;
    inputAudioFormat = AudioFormat.NOT_SET;
    outputAudioFormat = AudioFormat.NOT_SET;
  }

  public void setEnabled(boolean enabled) {
    this.isEnabled = enabled;
  }

  public boolean isEnabled() {
    return isEnabled;
  }

  @Override public AudioFormat configure(AudioFormat inputAudioFormat)
          throws UnhandledAudioFormatException {
    pendingInputAudioFormat = inputAudioFormat;
    pendingOutputAudioFormat = onConfigure(inputAudioFormat);
    return isActive() ? pendingOutputAudioFormat : AudioFormat.NOT_SET;
  }

  @Override public boolean isActive() {
    return !pendingOutputAudioFormat.equals(AudioFormat.NOT_SET);
  }

  @Override public void queueEndOfStream() {
    inputEnded = true;
  }

  @Override public ByteBuffer getOutput() {
    ByteBuffer outputBuffer = this.outputBuffer;
    this.outputBuffer = EMPTY_BUFFER;
    return outputBuffer;
  }

  @SuppressWarnings("ReferenceEquality")
  @Override public boolean isEnded() {
    return inputEnded && outputBuffer == EMPTY_BUFFER;
  }

  @Override public void flush(/*StreamMetadata streamMetadata*/) {
    outputBuffer = EMPTY_BUFFER;
    inputEnded = false;
    inputAudioFormat = pendingInputAudioFormat;
    outputAudioFormat = pendingOutputAudioFormat;
    onFlush(/*streamMetadata*/);
  }

  @Override public void reset() {
    outputBuffer = EMPTY_BUFFER;
    inputEnded = false;
    buffer = EMPTY_BUFFER;
    pendingInputAudioFormat = AudioFormat.NOT_SET;
    pendingOutputAudioFormat = AudioFormat.NOT_SET;
    inputAudioFormat = AudioFormat.NOT_SET;
    outputAudioFormat = AudioFormat.NOT_SET;
    onReset();
  }

  private ByteBuffer replaceOutputBuffer(int size) {
    if (buffer.capacity() < size) {
      buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
    } else {
      buffer.clear();
    }
    outputBuffer = buffer;
    return buffer;
  }

  /** Returns whether the current output buffer has any data remaining. */
  private boolean hasPendingOutput() {
    return outputBuffer.hasRemaining();
  }

  /** Called when the processor is configured for a new input format. */
  private AudioFormat onConfigure(AudioFormat inputAudioFormat)
          throws UnhandledAudioFormatException {
    // We only support PCM 16-bit encoding
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
      throw new UnhandledAudioFormatException(inputAudioFormat);
    }
    AudioFormat outputAudioFormat = inputAudioFormat;

    // Calculate sample offsets: (ms * sampleRate) / 1000
    tapSampleOffsets = new int[delaysMs.length];
    int maxDelaySamples = 0;
    for (int i = 0; i < delaysMs.length; i++) {
      // Multiply by channel count because samples are interleaved [L, R, L, R]
      tapSampleOffsets[i] = (int) ((delaysMs[i] * inputAudioFormat.sampleRate) / 1000.0) * inputAudioFormat.channelCount;
      maxDelaySamples = Math.max(maxDelaySamples, tapSampleOffsets[i]);
    }

    // Initialize circular buffer large enough for the longest delay
    delayLine = new short[maxDelaySamples + 1024];
    writeIndex = 0;

    return outputAudioFormat;
  }

  /** Called when the processor is {@linkplain AudioProcessor#flush(StreamMetadata) flushed}. */
  private void onFlush(/*StreamMetadata streamMetadata*/) {
    writeIndex = 0;
    if (delayLine != null) java.util.Arrays.fill(delayLine, (short) 0);
  }

  /** Called when the processor is reset. */
  private void onReset() {
    writeIndex = 0;
    if (delayLine != null) java.util.Arrays.fill(delayLine, (short) 0);
  }

  @Override public void queueInput(ByteBuffer inputBuffer) {
    int remaining = inputBuffer.remaining();
    if (remaining == 0) {
      return;
    }

    boolean isEnabled = isEnabled();
    if (isEnabled != this.wasEnabled) {
      java.util.Arrays.fill(delayLine, (short) 0);
      this.wasEnabled = isEnabled;
    }

    // Prepare output buffer
    ByteBuffer buffer = replaceOutputBuffer(remaining);

    if (!isEnabled) {
      // PASSTHROUGH MODE: Just copy input to output
      buffer.put(inputBuffer);
      //replaceOutputBuffer(inputBuffer.remaining()).put(inputBuffer).flip()
      //return;
    } else {
      // ECHO MODE: apply echo params
      while (inputBuffer.hasRemaining()) {
        short currentSample = inputBuffer.getShort();

        // Store in delay line
        delayLine[writeIndex] = currentSample;

        // Apply FFmpeg Logic: (Input * InputGain) + Sum(Taps * Decays)
        float mixedSample = currentSample * inputGain;

        for (int i = 0; i < tapSampleOffsets.length; i++) {
          int readIndex = (writeIndex - tapSampleOffsets[i] + delayLine.length) % delayLine.length;
          mixedSample += delayLine[readIndex] * decays[i];
        }

        // Apply Output Gain and clamp to prevent distortion
        int finalSample = Math.round(mixedSample * outputGain);
        finalSample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, finalSample));
        buffer.putShort((short) finalSample);
        writeIndex = (writeIndex + 1) % delayLine.length;
      }
    }

    // Tell the input buffer we consumed all data
    inputBuffer.position(inputBuffer.limit());
    buffer.flip();
  }
}
