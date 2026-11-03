// Gallery buttons and the copy button. The page works without this file.
(function () {
  var d = document;
  var calm = window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;
  var rail = d.getElementById("rail");
  var btns = d.querySelectorAll("[data-gal]");
  if (rail && btns.length) {
    var sync = function () {
      var max = rail.scrollWidth - rail.clientWidth - 2;
      btns[0].disabled = rail.scrollLeft <= 2;
      btns[1].disabled = rail.scrollLeft >= max;
    };
    btns.forEach(function (b) {
      b.addEventListener("click", function () {
        var li = rail.querySelector("li");
        var step = li ? li.offsetWidth + parseFloat(getComputedStyle(rail).columnGap || 0) : 240;
        var n = Math.max(1, Math.floor(rail.clientWidth / step) - 1);
        rail.scrollBy({ left: step * n * Number(b.dataset.gal), behavior: calm ? "auto" : "smooth" });
      });
    });
    rail.addEventListener("scroll", sync, { passive: true });
    window.addEventListener("resize", sync);
    sync();
  }
  d.querySelectorAll("[data-copy]").forEach(function (b) {
    var label = b.querySelector("span");
    var idle = label.textContent;
    b.addEventListener("click", function () {
      var text = d.getElementById(b.dataset.copy).textContent;
      var done = function () {
        label.textContent = b.dataset.done;
        setTimeout(function () { label.textContent = idle; }, 2000);
      };
      if (navigator.clipboard) navigator.clipboard.writeText(text).then(done, function () {});
    });
  });
})();
