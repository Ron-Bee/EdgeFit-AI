// EdgeFit AI model-fit demonstration (static, illustrative).
// Arithmetic on labeled example inputs only. Never fabricates
// measurements, never calls any backend.
(function () {
  "use strict";
  var FREE_GB = 3.2; // illustrative free memory, see page copy
  var pick = document.getElementById("modelpick");
  var out = document.getElementById("demoout");
  if (!pick || !out) return;
  function render() {
    var parts = String(pick.value).split("|");
    var size = parseFloat(parts[0]);
    var cls = parts[1];
    var verdict;
    if (size <= FREE_GB * 0.5) {
      verdict = "Comfortable fit: the weights leave headroom for " +
        "context, runtime overhead, and the OS.";
    } else if (size <= FREE_GB) {
      verdict = "Tight fit: workable with a small context, but expect " +
        "memory pressure. A real benchmark would confirm or refute this.";
    } else {
      verdict = "Likely too large for this illustrative memory figure: " +
        "the weights alone exceed free memory. Try a smaller quant.";
    }
    out.textContent = "";
    var box = document.createElement("div");
    box.className = "verdict";
    var head = document.createElement("p");
    var strong = document.createElement("strong");
    strong.textContent = cls + " (~" + size.toFixed(1) + " GB vs ~" +
      FREE_GB.toFixed(1) + " GB illustrative free): ";
    head.appendChild(strong);
    head.appendChild(document.createTextNode(verdict));
    var note = document.createElement("p");
    note.textContent = "Evidence status: EdgeFit has no published " +
      "measurements for this configuration — insufficient evidence. " +
      "Only a physical benchmark on your phone produces a number.";
    box.appendChild(head);
    box.appendChild(note);
    out.appendChild(box);
  }
  pick.addEventListener("change", render);
  render();
})();
