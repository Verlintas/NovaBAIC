/* BetterAIChat2 site — small, dependency-free interactions */
(function () {
  "use strict";

  var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* ---------- sticky nav shadow ---------- */
  var nav = document.getElementById("nav");
  function onScroll() { if (nav) nav.classList.toggle("scrolled", window.scrollY > 12); }
  onScroll();
  window.addEventListener("scroll", onScroll, { passive: true });

  /* ---------- GitHub star count ---------- */
  var starEl = document.getElementById("star-count");
  if (starEl) {
    fetch("https://api.github.com/repos/Verlintas/NovaBAIC", {
      headers: { Accept: "application/vnd.github+json" }
    })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (data) {
        if (!data || typeof data.stargazers_count !== "number") return;
        var n = data.stargazers_count;
        // Keep the plain "GitHub" label when there is nothing to brag about.
        if (n < 1) return;
        starEl.textContent = n >= 1000 ? (n / 1000).toFixed(1).replace(/\.0$/, "") + "k" : String(n);
      })
      .catch(function () { /* keep the fallback label */ });
  }

  /* ---------- scroll reveal ---------- */
  var revealEls = document.querySelectorAll(".reveal");
  if ("IntersectionObserver" in window) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add("in");
          io.unobserve(entry.target);
        }
      });
    }, { rootMargin: "0px 0px -8% 0px", threshold: 0.05 });
    revealEls.forEach(function (el) { io.observe(el); });
  } else {
    revealEls.forEach(function (el) { el.classList.add("in"); });
  }

  /* ---------- feature tabs ---------- */
  var tabs = Array.prototype.slice.call(document.querySelectorAll(".tab"));
  var panels = Array.prototype.slice.call(document.querySelectorAll(".panel"));
  tabs.forEach(function (tab) {
    tab.addEventListener("click", function () {
      var id = tab.getAttribute("data-tab");
      tabs.forEach(function (t) {
        var active = t === tab;
        t.classList.toggle("is-active", active);
        t.setAttribute("aria-selected", active ? "true" : "false");
      });
      panels.forEach(function (p) {
        p.classList.toggle("is-active", p.getAttribute("data-panel") === id);
      });
    });
  });

  /* ---------- hero demo timeline ---------- */
  var demo = {
    think: document.querySelector(".d-think"),
    thinkText: document.querySelector(".d-think-text"),
    answer: document.querySelector(".d-answer"),
    lines: Array.prototype.slice.call(document.querySelectorAll(".d-answer > *")),
    steps: Array.prototype.slice.call(document.querySelectorAll(".demo-body [data-step]")),
    replay: document.getElementById("demo-replay")
  };
  demo.steps.sort(function (a, b) {
    return Number(a.getAttribute("data-step")) - Number(b.getAttribute("data-step"));
  });
  var THINK_TEXT = "Quiet weekend: check the weather first, then plan the west shore. Crowds are the constraint — remember it.";
  var demoRunning = false;

  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  function typeInto(el, text, speed) {
    return new Promise(function (resolve) {
      var i = 0;
      el.textContent = "";
      (function tick() {
        if (!demoRunning) { resolve(); return; }
        if (i >= text.length) { resolve(); return; }
        el.textContent += text.charAt(i++);
        setTimeout(tick, speed);
      })();
    });
  }

  function resetDemo() {
    demo.steps.forEach(function (el) { el.classList.remove("show"); });
    if (demo.think) demo.think.classList.remove("is-done");
    if (demo.thinkText) demo.thinkText.textContent = "";
    demo.lines.forEach(function (l) { l.classList.remove("show"); });
    if (demo.replay) demo.replay.classList.remove("show");
  }

  function finishDemoInstant() {
    if (demo.thinkText) demo.thinkText.textContent = THINK_TEXT;
    if (demo.think) demo.think.classList.add("is-done");
    demo.steps.forEach(function (el) { el.classList.add("show"); });
    demo.lines.forEach(function (l) { l.classList.add("show"); });
    if (demo.replay) demo.replay.classList.add("show");
  }

  function playDemo() {
    if (demoRunning || !demo.steps.length) return;
    demoRunning = true;
    resetDemo();

    if (reduceMotion) {
      finishDemoInstant();
      demoRunning = false;
      return;
    }

    (async function () {
      await sleep(350);
      for (var i = 0; i < demo.steps.length; i++) {
        if (!demoRunning) return;
        var el = demo.steps[i];
        el.classList.add("show");
        if (el === demo.think) {
          await sleep(240);
          await typeInto(demo.thinkText, THINK_TEXT, 12);
          await sleep(280);
          demo.think.classList.add("is-done");
          await sleep(200);
        } else if (el === demo.answer) {
          for (var j = 0; j < demo.lines.length; j++) {
            if (!demoRunning) return;
            demo.lines[j].classList.add("show");
            await sleep(140);
          }
          await sleep(120);
        } else {
          await sleep(430);
        }
      }
      demo.replay.classList.add("show");
      demoRunning = false;
    })();
  }

  if (demo.replay) {
    demo.replay.addEventListener("click", function () {
      if (demoRunning) return;
      playDemo();
    });
  }

  // Start once the phone scrolls into view (or immediately if reduced motion).
  var phone = document.querySelector(".phone");
  if (phone && "IntersectionObserver" in window && !reduceMotion) {
    var demoIO = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          demoIO.disconnect();
          playDemo();
        }
      });
    }, { threshold: 0.35 });
    demoIO.observe(phone);
  } else if (phone) {
    playDemo();
  }

  /* ---------- download modal ---------- */
  var dlModal = document.getElementById("dl-modal");
  var dlTriggers = Array.prototype.slice.call(document.querySelectorAll(".js-download"));
  if (dlModal && dlTriggers.length) {
    var dlClose = dlModal.querySelector(".dl-close");
    var lastDlFocus = null;

    var openDownload = function (event) {
      if (event) event.preventDefault();
      lastDlFocus = document.activeElement;
      dlModal.hidden = false;
      document.body.classList.add("no-scroll");
      dlClose.focus();
    };
    var closeDownload = function () {
      dlModal.hidden = true;
      document.body.classList.remove("no-scroll");
      if (lastDlFocus && lastDlFocus.focus) lastDlFocus.focus();
    };

    dlTriggers.forEach(function (trigger) { trigger.addEventListener("click", openDownload); });
    dlClose.addEventListener("click", closeDownload);
    dlModal.addEventListener("click", function (event) {
      if (event.target === dlModal) closeDownload();
    });
    document.addEventListener("keydown", function (event) {
      if (!dlModal.hidden && event.key === "Escape") closeDownload();
    });

    // Fill in the latest version and APK size from the GitHub API.
    fetch("https://api.github.com/repos/Verlintas/NovaBAIC/releases/latest", {
      headers: { Accept: "application/vnd.github+json" }
    })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (data) {
        if (!data || !data.tag_name) return;
        var label = document.getElementById("dl-version");
        var size = "";
        if (Array.isArray(data.assets)) {
          for (var i = 0; i < data.assets.length; i++) {
            var asset = data.assets[i];
            if (/\.apk$/i.test(asset.name) && asset.size) {
              size = " · " + Math.round(asset.size / 1048576) + " MB";
              break;
            }
          }
        }
        if (label) label.textContent = "Latest " + data.tag_name + size + " · Android 8.0+ · signed APK";
      })
      .catch(function () { /* static fallback stays */ });
  }

  /* ---------- screenshot lightbox ---------- */
  var posters = Array.prototype.slice.call(document.querySelectorAll(".poster"));
  var lightbox = document.getElementById("lightbox");
  if (lightbox && posters.length) {
    var lbImg = document.getElementById("lb-img");
    var lbCaption = document.getElementById("lb-caption");
    var lbClose = lightbox.querySelector(".lb-close");
    var lbPrev = lightbox.querySelector(".lb-prev");
    var lbNext = lightbox.querySelector(".lb-next");
    var current = 0;
    var lastFocus = null;

    function renderLightbox() {
      var poster = posters[current];
      var img = poster.querySelector("img");
      lbImg.src = poster.getAttribute("data-full");
      lbImg.alt = img ? img.alt : "";
      lbCaption.textContent = poster.getAttribute("data-caption") || "";
    }

    function openLightbox(index) {
      current = (index + posters.length) % posters.length;
      lastFocus = document.activeElement;
      renderLightbox();
      lightbox.hidden = false;
      document.body.classList.add("no-scroll");
      lbClose.focus();
    }

    function closeLightbox() {
      lightbox.hidden = true;
      document.body.classList.remove("no-scroll");
      lbImg.removeAttribute("src");
      if (lastFocus && lastFocus.focus) lastFocus.focus();
    }

    posters.forEach(function (poster, index) {
      poster.addEventListener("click", function () { openLightbox(index); });
    });
    lbClose.addEventListener("click", closeLightbox);
    lbPrev.addEventListener("click", function () { openLightbox(current - 1); });
    lbNext.addEventListener("click", function () { openLightbox(current + 1); });
    lightbox.addEventListener("click", function (event) {
      if (event.target === lightbox) closeLightbox();
    });
    document.addEventListener("keydown", function (event) {
      if (lightbox.hidden) return;
      if (event.key === "Escape") closeLightbox();
      else if (event.key === "ArrowLeft") openLightbox(current - 1);
      else if (event.key === "ArrowRight") openLightbox(current + 1);
    });
  }
})();
