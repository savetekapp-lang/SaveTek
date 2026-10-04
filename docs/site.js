/* SaveTek — script for the text pages (privacy, terms, 404). No trackers, no external libraries.
   Language: the visitor's saved choice (shared with the home page), otherwise the browser language. */
(function () {
  "use strict";
  var root = document.documentElement;

  function applyLang(lang, remember) {
    root.lang = lang;
    root.dir = lang === "ar" ? "rtl" : "ltr";
    var title = root.getAttribute("data-title-" + lang);
    if (title) document.title = title;
    if (remember) { try { localStorage.setItem("savetek-lang", lang); } catch (e) { /* storage unavailable */ } }
  }

  var saved = null;
  try { saved = localStorage.getItem("savetek-lang"); } catch (e) { /* storage unavailable */ }
  if (saved === "ar" || saved === "en") {
    applyLang(saved, false);
  } else {
    var langs = navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language || "ar"];
    applyLang(langs.some(function (x) { return /^ar\b/i.test(x); }) ? "ar" : "en", false);
  }

  document.addEventListener("click", function (event) {
    if (event.target.closest("[data-toggle-lang]")) applyLang(root.lang === "ar" ? "en" : "ar", true);
  });
})();
