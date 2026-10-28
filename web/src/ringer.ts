// A plain two-tone ring from WebAudio, so no sound file has to be shipped.
// Browsers may only allow audio after a user gesture, so unlock() also runs on the first click;
// the Windows app's WebView plays without one.

let context: AudioContext | null = null;
/** All tones go through this, so stop() silences the ones already scheduled. */
let output: GainNode | null = null;
let timer: ReturnType<typeof setInterval> | undefined;

function ring() {
  if (!context || !output) return;
  const now = context.currentTime;
  for (const [offset, frequency] of [[0, 660], [0.25, 880], [1, 660], [1.25, 880]] as const) {
    const oscillator = context.createOscillator();
    const gain = context.createGain();
    oscillator.frequency.value = frequency;
    gain.gain.setValueAtTime(0.0001, now + offset);
    gain.gain.exponentialRampToValueAtTime(0.25, now + offset + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, now + offset + 0.22);
    oscillator.connect(gain).connect(output);
    oscillator.start(now + offset);
    oscillator.stop(now + offset + 0.25);
  }
}

export const ringer = {
  unlock() {
    try {
      context ??= new AudioContext();
      // suspended until a gesture in strict browsers, or after sleep and device changes
      if (context.state !== "running") context.resume().catch(() => {});
    } catch {
      // no WebAudio: calls still show, just silently
    }
  },
  start() {
    this.stop();
    this.unlock();
    if (!context) return;
    output = context.createGain();
    output.connect(context.destination);
    ring();
    timer = setInterval(ring, 3000);
  },
  stop() {
    clearInterval(timer);
    timer = undefined;
    output?.disconnect();
    output = null;
  },
};
