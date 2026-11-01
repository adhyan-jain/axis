import { animate, createTimeline, stagger } from 'animejs';

const $ = (id) => document.getElementById(id);
const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
const COMMAND = 'Send Atharva the Vercel link for axis-site';

/** Replayable walkthrough of the confirmation flow. `onState` drives the orb. */
export function createDemo(onState) {
  const said = $('demo-said'), steps = [...$('demo-steps').children], sheet = $('sheet');
  const result = $('demo-result'), replay = $('replay');
  let typing;

  function reset() {
    clearInterval(typing);
    said.textContent = ''; result.hidden = true; replay.hidden = true; sheet.hidden = true;
    steps.forEach((s) => { s.style.opacity = '0'; });
  }

  function typeCommand() {
    return new Promise((done) => {
      if (reduce) { said.textContent = COMMAND; return done(); }
      let i = 0;
      typing = setInterval(() => { said.textContent = COMMAND.slice(0, ++i); if (i >= COMMAND.length) { clearInterval(typing); done(); } }, 28);
    });
  }

  async function play() {
    reset(); onState('listening');
    await typeCommand();
    onState('thinking');
    if (reduce) steps.forEach((s) => { s.style.opacity = '1'; });
    else await createTimeline().add(steps, { opacity: [0, 1], translateY: [6, 0], duration: 380, delay: stagger(420) }).then();
    onState('speaking');
    sheet.hidden = false;
    if (!reduce) animate(sheet, { opacity: [0, 1], translateY: [10, 0], duration: 420, ease: 'outQuad' });
    onState('idle');
  }

  function finish(text) {
    const done = () => { sheet.hidden = true; result.textContent = text; result.hidden = false; replay.hidden = false; replay.focus(); };
    if (reduce) return done();
    animate(sheet, { opacity: 0, duration: 200, ease: 'outQuad', onComplete: done });
  }

  $('send').addEventListener('click', () => finish('Sent to Atharva Rao. You can undo for 10 seconds.'));
  $('cancel').addEventListener('click', () => finish('Cancelled. Nothing was sent.'));
  replay.addEventListener('click', play);
  return { play };
}
