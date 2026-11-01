import '@fontsource-variable/schibsted-grotesk';
import './style.css';
import gsap from 'gsap';
import { createOrb } from './orb.js';
import { createDemo } from './demo.js';

const orb = createOrb(document.getElementById('orb'));
const buttons = [...document.querySelectorAll('.orb-controls button')];

function setState(name) {
  orb.setState(name);
  buttons.forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.state === name)));
}
buttons.forEach((b) => b.addEventListener('click', () => setState(b.dataset.state)));

const demo = createDemo(setState);

// One orchestrated entrance, then the demo plays once.
const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
if (reduce) {
  demo.play();
} else {
  gsap.timeline({ defaults: { ease: 'power3.out' }, onComplete: demo.play })
    .from('.line', { yPercent: 40, opacity: 0, duration: 0.8, stagger: 0.12 })
    .from(['.badge', '.lede', '.orb-controls'], { opacity: 0, y: 12, duration: 0.6, stagger: 0.1 }, '-=0.4')
    .from('#orb', { scale: 0.85, opacity: 0, duration: 1 }, 0.1);
}
