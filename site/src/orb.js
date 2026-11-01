import * as THREE from 'three';

const STATES = {
  idle:      { speed: 0.35, amp: 0.05, hue: 0.0 },
  listening: { speed: 1.1,  amp: 0.28, hue: 0.35 },
  thinking:  { speed: 2.4,  amp: 0.16, hue: 0.8 },
  speaking:  { speed: 1.6,  amp: 0.34, hue: 0.15 },
};

const vertex = /* glsl */ `
  uniform float uTime, uAmp;
  varying vec3 vNormal; varying float vNoise;
  // cheap layered sines: smooth, no texture lookups, cheap on mobile GPUs
  float field(vec3 p){ return sin(p.x*2.1+uTime)*cos(p.y*2.3-uTime*.8)+sin(p.z*2.7+uTime*1.3)*.6; }
  void main(){
    float n = field(position*1.4);
    vNoise = n; vNormal = normalize(normalMatrix*normal);
    vec3 displaced = position + normal * n * uAmp;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(displaced,1.);
  }`;

const fragment = /* glsl */ `
  uniform vec3 uA, uB; uniform float uHue;
  varying vec3 vNormal; varying float vNoise;
  void main(){
    float rim = pow(1. - max(dot(normalize(vNormal), vec3(0.,0.,1.)), 0.), 2.2);
    float t = clamp(vNormal.y*.42 + .35 + vNoise*.10 + uHue*.2, 0., 1.);
    vec3 col = mix(uA, uB, t) + rim*.35;
    gl_FragColor = vec4(col, 1.);
  }`;

export function createOrb(canvas) {
  const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(35, 1, 0.1, 20);
  camera.position.z = 4.2;

  const uniforms = {
    uTime: { value: 0 }, uAmp: { value: STATES.idle.amp }, uHue: { value: 0 },
    uA: { value: new THREE.Color('#19C2E8') }, uB: { value: new THREE.Color('#6A5CFF') },
  };
  const mesh = new THREE.Mesh(
    new THREE.IcosahedronGeometry(1, 48),
    new THREE.ShaderMaterial({ uniforms, vertexShader: vertex, fragmentShader: fragment }),
  );
  scene.add(mesh);

  let target = STATES.idle, speed = target.speed, running = false, visible = true, last = 0, clock = 0;

  function resize() {
    const { clientWidth: w, clientHeight: h } = canvas;
    if (!w || !h) return;
    renderer.setSize(w, h, false);
    camera.aspect = w / h; camera.updateProjectionMatrix();
  }
  new ResizeObserver(resize).observe(canvas); resize();

  function frame(now) {
    if (!running) return;
    const dt = Math.min((now - last) / 1000, 0.05); last = now;
    speed += (target.speed - speed) * 0.06;
    clock += dt * speed;
    uniforms.uTime.value = clock;
    uniforms.uAmp.value += (target.amp - uniforms.uAmp.value) * 0.06;
    uniforms.uHue.value += (target.hue - uniforms.uHue.value) * 0.06;
    mesh.rotation.y += dt * 0.15 * speed;
    renderer.render(scene, camera);
    requestAnimationFrame(frame);
  }
  const start = () => { if (!running && visible && !document.hidden && !reduceMotion) { running = true; last = performance.now(); requestAnimationFrame(frame); } };
  const stop = () => { running = false; };

  // Battery rule from the app's design: draw nothing while off screen or in a hidden tab.
  new IntersectionObserver(([e]) => { visible = e.isIntersecting; visible ? start() : stop(); }).observe(canvas);
  document.addEventListener('visibilitychange', () => (document.hidden ? stop() : start()));

  renderer.render(scene, camera);
  start();
  return { setState(name) { target = STATES[name] ?? STATES.idle; if (reduceMotion) { uniforms.uAmp.value = target.amp; renderer.render(scene, camera); } } };
}
