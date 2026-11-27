(function () {
  "use strict";

  var LONG_PRESS_MS = 650;
  var MOVE_CANCEL_DP = 8; // CSS px == dp on Android WebView with device-width viewport
  var DOUBLE_TAP_MS = 280;

  var state = {
    chapterId: "",
    blocks: [],
    activeBlockId: null,
    selectedIds: new Set(),
    multiSelectMode: false,
    sourceMode: false,
    feedbackMap: {},
    annotationMap: {},
    foreshadowBlocks: new Set(),
    lastTapBlockId: null,
    lastTapAt: 0,
    singleTapTimer: null
  };

  var wysiwygRoot = document.getElementById("wysiwyg-root");
  var sourceRoot = document.getElementById("source-root");

  function bridge() {
    return window.QingJianBridge || null;
  }

  function escapeHtml(str) {
    return str
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function renderInlineMarkdown(raw) {
    var html = escapeHtml(raw);
    html = html.replace(/`([^`]+)`/g, "<code>$1</code>");
    html = html.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
    html = html.replace(/~~([^~]+)~~/g, "<del>$1</del>");
    html = html.replace(/\*([^*]+)\*/g, "<em>$1</em>");
    return html;
  }

  function renderBlockHtml(md) {
    var trimmed = md.trim();
    if (/^(-{3,}|\*{3,}|_{3,})$/.test(trimmed)) {
      return '<div class="muya-scene-break">场景分隔</div>';
    }
    if (trimmed.indexOf("### ") === 0) {
      return "<h3>" + renderInlineMarkdown(trimmed.slice(4)) + "</h3>";
    }
    if (trimmed.indexOf("## ") === 0) {
      return "<h2>" + renderInlineMarkdown(trimmed.slice(3)) + "</h2>";
    }
    if (trimmed.indexOf("# ") === 0) {
      return "<h1>" + renderInlineMarkdown(trimmed.slice(2)) + "</h1>";
    }
    if (trimmed.indexOf("> ") === 0) {
      var quoteText = trimmed.split("\n").map(function (l) {
        return l.replace(/^>\s?/, "");
      }).join("<br/>");
      return "<blockquote>" + renderInlineMarkdown(quoteText) + "</blockquote>";
    }
    if (/^[-*+]\s+/.test(trimmed)) {
      var items = trimmed.split("\n").map(function (l) {
        return "<li>" + renderInlineMarkdown(l.replace(/^[-*+]\s+/, "")) + "</li>";
      }).join("");
      return "<ul>" + items + "</ul>";
    }
    if (/^\d+\.\s+/.test(trimmed)) {
      var oItems = trimmed.split("\n").map(function (l) {
        return "<li>" + renderInlineMarkdown(l.replace(/^\d+\.\s+/, "")) + "</li>";
      }).join("");
      return "<ol>" + oItems + "</ol>";
    }
    return "<p>" + renderInlineMarkdown(trimmed).replace(/\n/g, "<br/>") + "</p>";
  }

  function splitMarkdownToBlocks(chapterId, markdown) {
    var raw = (markdown || "").replace(/\r\n/g, "\n").split(/\n\s*\n/);
    var blocks = [];
    for (var i = 0; i < raw.length; i++) {
      var part = raw[i].trim();
      if (part.length > 0) {
        blocks.push({
          id: "blk_" + chapterId + "_" + (blocks.length + 1),
          markdown: part
        });
      }
    }
    return blocks;
  }

  function serializeBlocks() {
    return state.blocks.map(function (b) { return b.markdown; }).join("\n\n");
  }

  function emitSelectionChanged() {
    var b = bridge();
    if (b && b.onSelectionChanged) {
      b.onSelectionChanged(JSON.stringify(Array.from(state.selectedIds)));
    }
  }

  function toggleBlockSelection(blockId) {
    if (state.selectedIds.has(blockId)) {
      state.selectedIds.delete(blockId);
    } else {
      state.selectedIds.add(blockId);
    }
    renderWysiwyg();
    emitSelectionChanged();
  }

  function attachThreeStateGestures(el, blockId) {
    var longPressTimer = null;
    var startX = 0;
    var startY = 0;
    var movedBeyondThreshold = false;
    var longPressFired = false;

    function clearLongPress() {
      if (longPressTimer !== null) {
        clearTimeout(longPressTimer);
        longPressTimer = null;
      }
    }

    el.addEventListener("pointerdown", function (ev) {
      if (ev.button && ev.button !== 0) return;
      startX = ev.clientX;
      startY = ev.clientY;
      movedBeyondThreshold = false;
      longPressFired = false;

      clearLongPress();
      longPressTimer = setTimeout(function () {
        longPressTimer = null;
        if (!movedBeyondThreshold) {
          longPressFired = true;
          state.activeBlockId = blockId;
          renderWysiwyg();
          var b = bridge();
          if (b && b.onBlockLongPress) {
            b.onBlockLongPress(blockId);
          }
        }
      }, LONG_PRESS_MS);
    }, { passive: true });

    el.addEventListener("pointermove", function (ev) {
      if (longPressTimer === null && movedBeyondThreshold) return;
      var dx = ev.clientX - startX;
      var dy = ev.clientY - startY;
      if (Math.hypot(dx, dy) > MOVE_CANCEL_DP) {
        movedBeyondThreshold = true;
        clearLongPress();
      }
    }, { passive: true });

    el.addEventListener("pointercancel", function () {
      clearLongPress();
    }, { passive: true });

    el.addEventListener("pointerup", function (ev) {
      clearLongPress();
      if (longPressFired || movedBeyondThreshold) return;

      // Support tablet external keyboard Ctrl+Click / Meta+Click non-adjacent multi-select
      if (ev.ctrlKey || ev.metaKey || state.multiSelectMode) {
        toggleBlockSelection(blockId);
        return;
      }

      var now = Date.now();
      if (state.lastTapBlockId === blockId && (now - state.lastTapAt) <= DOUBLE_TAP_MS) {
        if (state.singleTapTimer) {
          clearTimeout(state.singleTapTimer);
          state.singleTapTimer = null;
        }
        state.lastTapBlockId = null;
        state.lastTapAt = 0;
        state.activeBlockId = blockId;
        renderWysiwyg();
        var b2 = bridge();
        if (b2 && b2.onBlockDoubleTap) {
          b2.onBlockDoubleTap(blockId);
        }
      } else {
        state.lastTapBlockId = blockId;
        state.lastTapAt = now;
        if (state.singleTapTimer) clearTimeout(state.singleTapTimer);
        state.singleTapTimer = setTimeout(function () {
          state.singleTapTimer = null;
          state.activeBlockId = blockId;
          renderWysiwyg();
          var b1 = bridge();
          if (b1 && b1.onBlockSingleTap) {
            b1.onBlockSingleTap(blockId);
          }
        }, DOUBLE_TAP_MS);
      }
    });
  }

  function renderWysiwyg() {
    wysiwygRoot.className = "muya-canvas" + (state.multiSelectMode ? " multi-select-on" : "");
    wysiwygRoot.innerHTML = "";

    if (state.blocks.length === 0) {
      var empty = document.createElement("div");
      empty.className = "muya-meta-line";
      empty.textContent = "本章尚无正文段落，可切换源码模式输入或使用 AI 生成初稿。";
      wysiwygRoot.appendChild(empty);
      return;
    }

    for (var i = 0; i < state.blocks.length; i++) {
      var blk = state.blocks[i];
      var node = document.createElement("div");
      node.className = "muya-block";
      node.dataset.blockId = blk.id;
      if (state.activeBlockId === blk.id) node.classList.add("active");
      if (state.selectedIds.has(blk.id)) node.classList.add("selected");

      var row = document.createElement("div");
      row.className = "muya-block-row";

      var check = document.createElement("div");
      check.className = "muya-check";
      check.textContent = state.selectedIds.has(blk.id) ? "V" : "";
      row.appendChild(check);

      var content = document.createElement("div");
      content.className = "muya-content";
      content.innerHTML = renderBlockHtml(blk.markdown);

      var metaTags = [];
      var vote = state.feedbackMap[blk.id];
      if (vote === "UP") metaTags.push("已赞");
      if (vote === "DOWN") metaTags.push("已踩");
      if (state.foreshadowBlocks.has(blk.id)) metaTags.push("伏笔锚点");
      var note = state.annotationMap[blk.id];
      if (note && note.length > 0) metaTags.push("批注：" + note);

      if (metaTags.length > 0) {
        var metaEl = document.createElement("div");
        metaEl.className = "muya-meta-line";
        metaEl.innerHTML = '<span class="muya-meta-mark">' + escapeHtml(metaTags.join(" · ")) + "</span>";
        content.appendChild(metaEl);
      }

      row.appendChild(content);
      node.appendChild(row);
      attachThreeStateGestures(node, blk.id);
      wysiwygRoot.appendChild(node);
    }
  }

  sourceRoot.addEventListener("input", function () {
    var updated = sourceRoot.value;
    state.blocks = splitMarkdownToBlocks(state.chapterId, updated);
    var b = bridge();
    if (b && b.onDocumentChanged) {
      b.onDocumentChanged(updated);
    }
  });

  window.QingJianEditor = {
    loadDocument: function (payloadJson) {
      var data = JSON.parse(payloadJson);
      state.chapterId = data.chapterId || "";
      state.blocks = splitMarkdownToBlocks(state.chapterId, data.markdown || "");
      state.feedbackMap = data.feedback || {};
      state.annotationMap = data.annotations || {};
      state.foreshadowBlocks = new Set(data.foreshadowBlockIds || []);
      if (data.activeBlockId) state.activeBlockId = data.activeBlockId;
      if (state.sourceMode) {
        sourceRoot.value = data.markdown || "";
      } else {
        renderWysiwyg();
      }
    },

    setTheme: function (isDark, accentHex, maxWidthPx) {
      document.body.classList.toggle("dark", !!isDark);
      if (accentHex) {
        document.documentElement.style.setProperty("--qj-accent", accentHex);
      }
      if (maxWidthPx) {
        document.documentElement.style.setProperty("--qj-max-width", maxWidthPx + "px");
      }
    },

    setMultiSelectMode: function (enabled) {
      state.multiSelectMode = !!enabled;
      if (!enabled) {
        state.selectedIds.clear();
        emitSelectionChanged();
      }
      renderWysiwyg();
    },

    setSourceMode: function (enabled) {
      var next = !!enabled;
      if (state.sourceMode === next) return;
      state.sourceMode = next;
      if (next) {
        var fullMd = serializeBlocks();
        sourceRoot.value = fullMd;
        wysiwygRoot.classList.add("hidden");
        sourceRoot.classList.remove("hidden");
        // Preserve approximate cursor position at active block
        if (state.activeBlockId) {
          var targetBlk = state.blocks.find(function (b) { return b.id === state.activeBlockId; });
          if (targetBlk) {
            var pos = fullMd.indexOf(targetBlk.markdown);
            if (pos >= 0) {
              sourceRoot.focus();
              sourceRoot.setSelectionRange(pos, pos);
            }
          }
        }
      } else {
        var cursorPos = sourceRoot.selectionStart || 0;
        var beforeCursor = sourceRoot.value.slice(0, cursorPos);
        var blockIdx = beforeCursor.split(/\n\s*\n/).filter(function (s) { return s.trim().length > 0; }).length - 1;
        state.blocks = splitMarkdownToBlocks(state.chapterId, sourceRoot.value);
        if (blockIdx >= 0 && blockIdx < state.blocks.length) {
          state.activeBlockId = state.blocks[blockIdx].id;
        }
        sourceRoot.classList.add("hidden");
        wysiwygRoot.classList.remove("hidden");
        renderWysiwyg();
      }
    },

    insertSceneBreak: function () {
      var idx = state.blocks.findIndex(function (b) { return b.id === state.activeBlockId; });
      var insertAt = idx >= 0 ? idx + 1 : state.blocks.length;
      var nextBlocks = state.blocks.slice();
      nextBlocks.splice(insertAt, 0, { id: "tmp_hr", markdown: "---" });
      var merged = nextBlocks.map(function (b) { return b.markdown; }).join("\n\n");
      state.blocks = splitMarkdownToBlocks(state.chapterId, merged);
      if (state.sourceMode) {
        sourceRoot.value = merged;
      } else {
        renderWysiwyg();
      }
      var b = bridge();
      if (b && b.onDocumentChanged) {
        b.onDocumentChanged(merged);
      }
    },

    scrollToAndHighlightBlock: function (blockId) {
      if (state.sourceMode) {
        window.QingJianEditor.setSourceMode(false);
      }
      state.activeBlockId = blockId;
      renderWysiwyg();
      var el = wysiwygRoot.querySelector('[data-block-id="' + blockId + '"]');
      if (el) {
        el.scrollIntoView({ behavior: "smooth", block: "center" });
        el.classList.add("flash-anchor");
        setTimeout(function () {
          el.classList.remove("flash-anchor");
        }, 2200);
      }
    }
  };

  var b = bridge();
  if (b && b.onEditorReady) {
    b.onEditorReady();
  }
})();
