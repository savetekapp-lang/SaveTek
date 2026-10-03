/* SaveTek — سكربت الموقع (بدون أي أدوات تتبع أو مكتبات خارجية)
   1. تبديل اللغة بين العربية والإنجليزية (ويُحفظ الاختيار في المتصفح فقط).
   2. زر التحميل: يقرأ version.json ويعرض رقم الإصدار وحجمه.
   3. روابط GitHub تُستنتج تلقائياً من عنوان الموقع.
*/
(function () {
  "use strict";

  // ---------- اللغة ----------
  var root = document.documentElement;

  function savedLang() {
    try {
      var fromUrl = new URLSearchParams(location.search).get("lang");
      if (fromUrl === "ar" || fromUrl === "en") return fromUrl;
      var stored = localStorage.getItem("savetek-lang");
      if (stored === "ar" || stored === "en") return stored;
    } catch (e) { /* التخزين غير متاح: نستخدم العربية */ }
    return "ar";
  }

  function applyLang(lang) {
    root.lang = lang;
    root.dir = lang === "ar" ? "rtl" : "ltr";
    var title = root.getAttribute("data-title-" + lang);
    if (title) document.title = title;
    try { localStorage.setItem("savetek-lang", lang); } catch (e) { /* تجاهل */ }
  }

  applyLang(savedLang());

  document.addEventListener("click", function (event) {
    var btn = event.target.closest("[data-toggle-lang]");
    if (!btn) return;
    applyLang(root.lang === "ar" ? "en" : "ar");
  });

  // ---------- روابط المستودع ----------
  // على GitHub Pages يكون العنوان: https://USER.github.io/REPO/
  function repoFromLocation() {
    if (!/\.github\.io$/.test(location.hostname)) return null;
    var user = location.hostname.split(".")[0];
    var repo = location.pathname.split("/").filter(Boolean)[0];
    return repo ? "https://github.com/" + user + "/" + repo : null;
  }

  function setRepoLinks(repoUrl) {
    if (!repoUrl) return;
    document.querySelectorAll("[data-repo-link]").forEach(function (a) {
      a.href = repoUrl + (a.getAttribute("data-repo-link") || "");
    });
  }

  setRepoLinks(repoFromLocation());

  // ---------- صور الشاشات: إظهار مكان بديل إذا لم توجد الصورة بعد ----------
  document.querySelectorAll(".shot > img").forEach(function (img) {
    function missing() { img.parentElement.classList.add("missing"); }
    if (img.complete && img.naturalWidth === 0) missing();
    img.addEventListener("error", missing);
  });

  // ---------- زر التحميل ----------
  var buttons = document.querySelectorAll("[data-download]");
  if (!buttons.length) return;

  // رابط احتياطي: صفحة آخر إصدار على GitHub (إذا تعذّرت قراءة version.json)
  var fallbackRepo = repoFromLocation();
  if (fallbackRepo) {
    buttons.forEach(function (btn) { btn.href = fallbackRepo + "/releases/latest"; });
  }

  function formatSize(bytes) {
    if (!bytes) return "";
    return (bytes / (1024 * 1024)).toFixed(0) + " MB";
  }

  fetch("version.json", { cache: "no-store" })
    .then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
    .then(function (info) {
      // الملف الرئيسي يناسب أغلب الجوالات الحديثة (arm64)
      var main = (info.abis && info.abis["arm64-v8a"]) || { url: info.apkUrl, sizeBytes: info.apkSizeBytes };
      var size = formatSize(main.sizeBytes);
      buttons.forEach(function (btn) {
        if (main.url) btn.href = main.url;
        btn.querySelectorAll("[data-version]").forEach(function (el) { el.textContent = info.versionName; });
        btn.querySelectorAll("[data-size]").forEach(function (el) { el.textContent = size; });
      });
      if (info.releasePage) {
        var repoUrl = info.releasePage.replace(/\/releases\/.*$/, "");
        setRepoLinks(repoUrl);
      }
    })
    .catch(function () { /* نُبقي الرابط الاحتياطي إلى صفحة الإصدارات */ });
})();
