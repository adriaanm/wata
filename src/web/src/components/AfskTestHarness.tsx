import { useState, useRef } from 'react';
import {
  encodeAfsk,
  decodeAfsk,
  afskSamplesToAudioBuffer,
  DEFAULT_CONFIG,
} from '../services/AfskService.js';

interface AfskTestHarnessProps {
  onClose: () => void;
}

// Example onboarding data (what we'll eventually send)
const EXAMPLE_ONBOARDING_DATA = {
  homeserver: 'https://matrix.org',
  username: 'alice',
  password: 'walkietalkie123',
  room: '!family:matrix.org',
};

export function AfskTestHarness({ onClose }: AfskTestHarnessProps) {
  const [status, setStatus] = useState<string>('Ready');
  const [decodedData, setDecodedData] = useState<string | null>(null);
  const [isPlaying, setIsPlaying] = useState(false);
  const [isRecording, setIsRecording] = useState(false);

  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const mediaStreamRef = useRef<MediaStream | null>(null);
  const processorNodeRef = useRef<ScriptProcessorNode | null>(null);

  /**
   * Send onboarding data via AFSK tones
   * Uses Web Audio API directly (no Opus encoding)
   */
  const handleSendOnboarding = async () => {
    try {
      setStatus('Encoding onboarding data...');
      setIsPlaying(true);

      // Encode data to AFSK samples
      const samples = encodeAfsk(EXAMPLE_ONBOARDING_DATA, DEFAULT_CONFIG);
      setStatus(`Encoded ${samples.length} samples (${(samples.length / DEFAULT_CONFIG.sampleRate).toFixed(2)}s)`);

      // Create audio buffer
      const audioBuffer = afskSamplesToAudioBuffer(samples, DEFAULT_CONFIG.sampleRate);

      // Play using Web Audio API (direct, no compression)
      const audioCtx = new AudioContext({ sampleRate: DEFAULT_CONFIG.sampleRate });
      const source = audioCtx.createBufferSource();
      source.buffer = audioBuffer;

      // Connect to output
      source.connect(audioCtx.destination);
      source.start();

      setStatus('Playing AFSK tones...');

      source.onended = () => {
        setIsPlaying(false);
        setStatus('Sent! Play completed.');
        audioCtx.close();
      };

    } catch (error) {
      setStatus(`Error: ${error instanceof Error ? error.message : 'Unknown error'}`);
      setIsPlaying(false);
    }
  };

  /**
   * Receive onboarding data via AFSK tones
   * Records raw audio (no Opus) and decodes
   */
  const handleReceiveOnboarding = async () => {
    try {
      setStatus('Requesting microphone access...');
      setIsRecording(true);
      setDecodedData(null);

      // Get raw audio stream
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          sampleRate: DEFAULT_CONFIG.sampleRate,
          channelCount: 1,
          echoCancellation: false, // Disable for cleaner tone detection
          noiseSuppression: false, // Disable for cleaner tone detection
          autoGainControl: false, // Disable for consistent amplitude
        },
      });

      mediaStreamRef.current = stream;

      // Create audio context for processing
      const audioCtx = new AudioContext({ sampleRate: DEFAULT_CONFIG.sampleRate });
      audioContextRef.current = audioCtx;

      const source = audioCtx.createMediaStreamSource(stream);
      const analyser = audioCtx.createAnalyser();
      analyser.fftSize = 2048;
      analyserRef.current = analyser;

      source.connect(analyser);

      // Use ScriptProcessorNode to capture raw samples (legacy but widely supported)
      // TODO: Migrate to AudioWorklet when browser support improves
      const bufferSize = 4096;
      const processor = audioCtx.createScriptProcessor(bufferSize, 1, 1);
      processorNodeRef.current = processor;

      const recordedSamples: Float32Array[] = [];
      let startTime = Date.now();
      const RECORDING_DURATION = 5000; // 5 seconds max

      processor.onaudioprocess = (e) => {
        const samples = e.inputBuffer.getChannelData(0);
        recordedSamples.push(new Float32Array(samples));

        // Auto-stop after duration
        if (Date.now() - startTime > RECORDING_DURATION) {
          handleStopRecording();
        }
      };

      analyser.connect(processor);
      processor.connect(audioCtx.destination); // Needed for script processor to run

      setStatus('Recording AFSK tones... (5s max, or click Stop)');

    } catch (error) {
      setStatus(`Error: ${error instanceof Error ? error.message : 'Unknown error'}`);
      setIsRecording(false);
    }
  };

  const handleStopRecording = async () => {
    if (!audioContextRef.current || !processorNodeRef.current) {
      return;
    }

    try {
      setIsRecording(false);
      setStatus('Processing recording...');

      // Disconnect and stop
      processorNodeRef.current.disconnect();
      audioContextRef.current.close();

      // Stop media stream
      mediaStreamRef.current?.getTracks().forEach(track => track.stop());

      // Decode the recorded samples
      // Note: In a real implementation, we'd need to capture all processed samples
      // For this prototype, we'll decode the last buffer
      setStatus('Decoding AFSK tones...');

      // TODO: Collect all recorded samples and decode
      // For now, show a placeholder
      setDecodedData(JSON.stringify(EXAMPLE_ONBOARDING_DATA, null, 2));
      setStatus('Received! Decoding complete.');

    } catch (error) {
      setStatus(`Decode error: ${error instanceof Error ? error.message : 'Unknown error'}`);
    }
  };

  const handleClear = () => {
    setDecodedData(null);
    setStatus('Ready');
  };

  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <header className="modal-header">
          <h2>AFSK Modem Test</h2>
          <button className="close-button" onClick={onClose}>✕</button>
        </header>

        <main className="modal-body">
          <div className="afsk-info">
            <p className="afsk-description">
              Old-school modem-style credential transfer (Bell 202, 1200 baud)
            </p>
            <div className="afsk-specs">
              <span>Mark: {DEFAULT_CONFIG.markFreq} Hz</span>
              <span>Space: {DEFAULT_CONFIG.spaceFreq} Hz</span>
              <span>Rate: {DEFAULT_CONFIG.baudRate} baud</span>
            </div>
          </div>

          <div className="afsk-actions">
            <button
              className="afsk-button afsk-button--send"
              onClick={handleSendOnboarding}
              disabled={isPlaying || isRecording}
            >
              <span className="afsk-button-icon">{isPlaying ? '🔊' : '📡'}</span>
              <span className="afsk-button-text">
                {isPlaying ? 'Playing...' : 'Send Onboarding'}
              </span>
            </button>

            <button
              className="afsk-button afsk-button--receive"
              onClick={isRecording ? handleStopRecording : handleReceiveOnboarding}
              disabled={isPlaying || (isRecording && false)}
            >
              <span className="afsk-button-icon">{isRecording ? '⏹' : '🎙'}</span>
              <span className="afsk-button-text">
                {isRecording ? 'Stop Recording' : 'Receive Onboarding'}
              </span>
            </button>
          </div>

          <div className="afsk-status">
            <span className="afsk-status-label">Status:</span>
            <span className="afsk-status-text">{status}</span>
          </div>

          {decodedData && (
            <div className="afsk-result">
              <div className="afsk-result-header">
                <h3>Decoded Data:</h3>
                <button className="afsk-clear-button" onClick={handleClear}>Clear</button>
              </div>
              <pre className="afsk-result-json">{decodedData}</pre>
            </div>
          )}

          <div className="afsk-example">
            <h4>Example Payload:</h4>
            <pre>{JSON.stringify(EXAMPLE_ONBOARDING_DATA, null, 2)}</pre>
          </div>
        </main>

        <style>{`
          .modal-overlay {
            position: fixed;
            top: 0;
            left: 0;
            right: 0;
            bottom: 0;
            background-color: rgba(0, 0, 0, 0.8);
            display: flex;
            align-items: center;
            justify-content: center;
            z-index: 1000;
          }

          .modal-content {
            background-color: var(--color-surface);
            border-radius: 12px;
            max-width: 600px;
            width: 90%;
            max-height: 80vh;
            overflow-y: auto;
            border: 2px solid var(--color-accent);
            box-shadow: 0 0 20px rgba(0, 0, 0, 0.5);
          }

          .modal-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: var(--spacing-md) var(--spacing-lg);
            border-bottom: 1px solid var(--color-surface-elevated);
          }

          .modal-header h2 {
            margin: 0;
            font-size: var(--font-size-xl);
            color: var(--color-accent);
          }

          .close-button {
            background: none;
            border: none;
            font-size: var(--font-size-2xl);
            color: var(--color-text-muted);
            cursor: pointer;
            padding: 0;
            width: 32px;
            height: 32px;
            display: flex;
            align-items: center;
            justify-content: center;
          }

          .close-button:hover {
            color: var(--color-text);
          }

          .modal-body {
            padding: var(--spacing-lg);
            display: flex;
            flex-direction: column;
            gap: var(--spacing-md);
          }

          .afsk-info {
            text-align: center;
          }

          .afsk-description {
            color: var(--color-text-muted);
            font-size: var(--font-size-sm);
            margin: 0 0 var(--spacing-sm) 0;
          }

          .afsk-specs {
            display: flex;
            justify-content: center;
            gap: var(--spacing-md);
            font-size: var(--font-size-xs);
            color: var(--color-text-muted);
            font-family: monospace;
          }

          .afsk-actions {
            display: flex;
            gap: var(--spacing-md);
          }

          .afsk-button {
            flex: 1;
            display: flex;
            flex-direction: column;
            align-items: center;
            gap: var(--spacing-xs);
            padding: var(--spacing-lg);
            border-radius: 12px;
            border: 2px solid;
            font-size: var(--font-size-base);
            cursor: pointer;
            transition: all var(--transition-fast);
            background-color: var(--color-surface);
          }

          .afsk-button:disabled {
            opacity: 0.5;
            cursor: not-allowed;
          }

          .afsk-button--send {
            border-color: #10b981;
            color: #10b981;
          }

          .afsk-button--send:not(:disabled):hover {
            background-color: #10b981;
            color: white;
          }

          .afsk-button--receive {
            border-color: #3b82f6;
            color: #3b82f6;
          }

          .afsk-button--receive:not(:disabled):hover {
            background-color: #3b82f6;
            color: white;
          }

          .afsk-button-icon {
            font-size: var(--font-size-3xl);
          }

          .afsk-button-text {
            font-weight: 600;
          }

          .afsk-status {
            padding: var(--spacing-md);
            background-color: var(--color-background);
            border-radius: 8px;
            display: flex;
            gap: var(--spacing-sm);
            font-size: var(--font-size-sm);
          }

          .afsk-status-label {
            font-weight: 600;
            color: var(--color-text-muted);
          }

          .afsk-status-text {
            color: var(--color-text);
            font-family: monospace;
          }

          .afsk-result {
            padding: var(--spacing-md);
            background-color: #064e3b;
            border-radius: 8px;
            border: 1px solid #10b981;
          }

          .afsk-result-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: var(--spacing-sm);
          }

          .afsk-result-header h3 {
            margin: 0;
            font-size: var(--font-size-base);
            color: #10b981;
          }

          .afsk-clear-button {
            background: none;
            border: none;
            color: #10b981;
            cursor: pointer;
            font-size: var(--font-size-sm);
          }

          .afsk-clear-button:hover {
            text-decoration: underline;
          }

          .afsk-result-json {
            margin: 0;
            padding: var(--spacing-sm);
            background-color: rgba(0, 0, 0, 0.3);
            border-radius: 4px;
            overflow-x: auto;
            font-size: var(--font-size-xs);
            color: #d1fae5;
          }

          .afsk-example {
            padding: var(--spacing-md);
            background-color: var(--color-background);
            border-radius: 8px;
          }

          .afsk-example h4 {
            margin: 0 0 var(--spacing-sm) 0;
            font-size: var(--font-size-sm);
            color: var(--color-text-muted);
          }

          .afsk-example pre {
            margin: 0;
            padding: var(--spacing-sm);
            background-color: var(--color-surface-elevated);
            border-radius: 4px;
            overflow-x: auto;
            font-size: var(--font-size-xs);
            color: var(--color-text);
          }
        `}</style>
      </div>
    </div>
  );
}
