// A plain two-tone ring from WebAudio, so no sound file has to be shipped.
// Browsers only allow audio after a user gesture; unlock() runs on the first click.

let context: AudioContext | null = null;
let timer: ReturnType<typeof setInterval> | undefined;

function ring() {
  if (!context) return;
  const now = context.currentTime;
  for (const [offset, frequency] of [[0, 660], [0.25, 880], [1, 660], [1.25, 880]] as const) {
    const oscillator = context.createOscillator();
    const gain = context.createGain();
    oscillator.frequency.value = frequency;
    gain.gain.setValueAtTime(0.0001, now + offset);
    gain.gain.exponentialRampToValueAtTime(0.25, now + offset + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, now + offset + 0.22);
    oscillator.connect(gain).connect(context.destination);
    oscillator.start(now + offset);
    oscillator.stop(now + offset + 0.25);
  }
}

export const ringer = {
  unlock() {
    context ??= new AudioContext();
    if (context.state === "suspended") context.resume();
  },
  start() {
    this.stop();
    ring();
    timer = setInterval(ring, 3000);
  },
  stop() {
    clearInterval(timer);
    timer = undefined;
  },
};
