package com.morealm.app.ui.reader

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import com.morealm.app.domain.entity.ThemeEntity
import com.morealm.app.ui.theme.LocalMoRealmColors
import com.morealm.app.ui.theme.toComposeColor
import com.morealm.app.presentation.reader.PageTurnMode

// ── WebView ─────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReaderWebView(
    content: String,
    chapterTitle: String,
    backgroundColor: Int,
    textColor: Int,
    accentColor: Int,
    pageTurnMode: PageTurnMode,
    fontFamily: String = "noto_serif_sc",
    fontSize: Float = 17f,
    lineHeight: Float = 2.0f,
    backgroundTexture: String? = null,
    customFontUri: String = "",
    startFromLastPage: Boolean = false,
    showChapterName: Boolean = true,
    showTimeBattery: Boolean = true,
    tapLeftAction: String = "prev",
    paragraphSpacing: Float = 1.4f,
    marginHorizontal: Int = 24,
    marginTop: Int = 24,
    marginBottom: Int = 24,
    customCss: String = "",
    customBgImage: String = "",
    onTapZone: (zone: String) -> Unit,
    onLongPress: () -> Unit,
    onSwipeBack: () -> Unit = {},
    onProgress: (Int) -> Unit = {},
    onScrollNearBottom: () -> Unit = {},
    onVisibleChapterChanged: (Int) -> Unit = {},
    onTextSelected: (String) -> Unit = {},
    onImageClick: (String) -> Unit = {},
    onSpeakSelected: (String) -> Unit = {},
    ttsScrollProgress: Float = -1f,
    pageAnim: String = "none",
    dualPage: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val bgHex = String.format("#%06X", 0xFFFFFF and backgroundColor)
    val textHex = String.format("#%06X", 0xFFFFFF and textColor)
    val accentHex = String.format("#%06X", 0xFFFFFF and accentColor)
    val html = remember(content, chapterTitle, bgHex, textHex, accentHex, pageTurnMode, fontFamily, fontSize, lineHeight, backgroundTexture, customFontUri, showChapterName, showTimeBattery, tapLeftAction, paragraphSpacing, marginHorizontal, marginTop, marginBottom, customCss, customBgImage, pageAnim, dualPage) {
        buildReaderHtml(content, chapterTitle, bgHex, textHex, accentHex, pageTurnMode, fontFamily, fontSize, lineHeight, backgroundTexture = backgroundTexture, customFontUri = customFontUri, showChapterName = showChapterName, showTimeBattery = showTimeBattery, tapLeftAction = tapLeftAction, paragraphSpacing = paragraphSpacing, marginHorizontal = marginHorizontal, marginTop = marginTop, marginBottom = marginBottom, customCss = customCss, customBgImage = customBgImage, pageAnim = pageAnim, dualPage = dualPage)
    }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var lastContent by remember { mutableStateOf("") }
    var lastTitle by remember { mutableStateOf("") }
    var lastMode by remember { mutableStateOf(pageTurnMode) }
    var navigatedBack by remember { mutableStateOf(false) }

    // Track when startFromLastPage changes to true
    LaunchedEffect(startFromLastPage) {
        if (startFromLastPage) navigatedBack = true
    }

    val tapZoneRef = rememberUpdatedState(onTapZone)
    val longPressRef = rememberUpdatedState(onLongPress)
    val swipeBackRef = rememberUpdatedState(onSwipeBack)
    val progressRef = rememberUpdatedState(onProgress)
    val scrollNearBottomRef = rememberUpdatedState(onScrollNearBottom)
    val visibleChapterRef = rememberUpdatedState(onVisibleChapterChanged)
    val textSelectedRef = rememberUpdatedState(onTextSelected)
    val imageClickRef = rememberUpdatedState(onImageClick)
    val speakSelectedRef = rememberUpdatedState(onSpeakSelected)

    // TTS auto-scroll: when ttsScrollProgress changes, scroll WebView
    LaunchedEffect(ttsScrollProgress) {
        if (ttsScrollProgress >= 0f) {
            webViewRef?.evaluateJavascript(
                "(function(){var sh=document.body.scrollHeight-window.innerHeight;" +
                "if(sh>0)window.scrollTo({top:Math.round(sh*$ttsScrollProgress),behavior:'smooth'});})()",
                null
            )
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = true
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = false
                setBackgroundColor(backgroundColor)
                webViewClient = WebViewClient()
                isVerticalScrollBarEnabled = pageTurnMode == PageTurnMode.SCROLL
                isHorizontalScrollBarEnabled = false
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onTap(zone: String) { tapZoneRef.value(zone) }
                    @JavascriptInterface
                    fun onLong() { longPressRef.value() }
                    @JavascriptInterface
                    fun onSwipe(direction: String) {
                        if (direction == "right") swipeBackRef.value()
                    }
                    @JavascriptInterface
                    fun onScrollEnd() {
                        tapZoneRef.value("next")
                    }
                    @JavascriptInterface
                    fun onProgress(pct: Int) {
                        progressRef.value(pct)
                    }
                    @JavascriptInterface
                    fun onNearBottom() {
                        scrollNearBottomRef.value()
                    }
                    @JavascriptInterface
                    fun onChapterVisible(index: Int) {
                        visibleChapterRef.value(index)
                    }
                    @JavascriptInterface
                    fun onTextSelected(text: String) {
                        textSelectedRef.value(text)
                    }
                    @JavascriptInterface
                    fun onImageClick(src: String) {
                        imageClickRef.value(src)
                    }
                    @JavascriptInterface
                    fun onSpeakSelected(text: String) {
                        speakSelectedRef.value(text)
                    }
                }, "MoRealm")
                webViewRef = this
            }
        },
        update = { webView ->
            webView.isVerticalScrollBarEnabled = pageTurnMode == PageTurnMode.SCROLL
            val contentChanged = content != lastContent || chapterTitle != lastTitle
            val modeChanged = pageTurnMode != lastMode
            val isScrollMode = pageTurnMode == PageTurnMode.SCROLL

            if (contentChanged || (lastContent.isEmpty() && content.isNotEmpty())) {
                // Content actually changed — must reload HTML
                val isAppend = isScrollMode && lastContent.isNotEmpty()
                        && content.length > lastContent.length
                        && content.startsWith(lastContent.take(500))

                val shouldGoToLastPage = navigatedBack && pageTurnMode != PageTurnMode.SCROLL
                navigatedBack = false
                lastContent = content
                lastTitle = chapterTitle
                lastMode = pageTurnMode

                if (isAppend) {
                    webView.evaluateJavascript(
                        "(window.pageYOffset||document.documentElement.scrollTop).toString()"
                    ) { scrollY ->
                        val y = scrollY?.replace("\"", "")?.toIntOrNull() ?: 0
                        webView.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                view?.evaluateJavascript("window.scrollTo(0,$y)", null)
                                webView.webViewClient = WebViewClient()
                            }
                        }
                        webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
                    }
                } else {
                    webView.scrollTo(0, 0)
                    if (shouldGoToLastPage) {
                        webView.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                view?.evaluateJavascript("_startFromLast=true;_relayout();", null)
                                webView.webViewClient = WebViewClient()
                            }
                        }
                    }
                    webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
                }
            } else if (modeChanged) {
                // Page turn mode changed — reload but preserve scroll
                lastMode = pageTurnMode
                webView.evaluateJavascript(
                    "(window.pageYOffset||document.documentElement.scrollTop).toString()"
                ) { scrollY ->
                    val y = scrollY?.replace("\"", "")?.toIntOrNull() ?: 0
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript("window.scrollTo(0,$y)", null)
                            webView.webViewClient = WebViewClient()
                        }
                    }
                    webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
                }
            } else {
                // No content or mode change — just update live styles
                webView.evaluateJavascript("""
                    (function(){
                      document.body.style.fontSize='${fontSize.toInt()}px';
                      document.body.style.lineHeight='$lineHeight';
                      document.body.style.color='$textHex';
                      document.documentElement.style.setProperty('--bg','$bgHex');
                      document.documentElement.style.setProperty('--fg','$textHex');
                      document.documentElement.style.setProperty('--accent','$accentHex');
                      var ct=document.querySelector('h1.ct');if(ct)ct.style.color='$accentHex';
                      var dc=document.querySelector('.dc');if(dc)dc.style.color='$accentHex';
                      if(typeof _relayout==='function') _relayout();
                    })();
                """.trimIndent(), null)
            }
        },
        modifier = modifier,
    )
}

private fun buildReaderHtml(
    content: String, title: String,
    bg: String, fg: String, accent: String,
    mode: PageTurnMode,
    fontFamily: String = "noto_serif_sc",
    fontSize: Float = 17f,
    lineHeight: Float = 2.0f,
    titleFontWeight: Int = 500,
    backgroundTexture: String? = null,
    customFontUri: String = "",
    showChapterName: Boolean = true,
    showTimeBattery: Boolean = true,
    tapLeftAction: String = "prev",
    paragraphSpacing: Float = 1.4f,
    marginHorizontal: Int = 24,
    marginTop: Int = 24,
    marginBottom: Int = 24,
    customCss: String = "",
    customBgImage: String = "",
    pageAnim: String = "none",
    dualPage: Boolean = false,
): String {
    val escapedTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val cleaned = content.replace("\uFEFF", "").replace("\u200B", "").replace("\r", "")

    val isHtml = cleaned.trimStart().startsWith("<") && (cleaned.contains("<p") || cleaned.contains("<div") || cleaned.contains("<img"))

    val escapedContent = if (isHtml) {
        cleaned
    } else {
        val rawLines = cleaned.lines().filter { it.isNotBlank() }
        val bodyLines = if (rawLines.isNotEmpty() && rawLines[0].trim() == title.trim()) {
            rawLines.drop(1)
        } else rawLines
        bodyLines
            .map { line ->
                val c = line.trim().replace(Regex("^#{1,6}\\s*"), "")
                c.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            }
            .filter { it.isNotEmpty() }
            .mapIndexed { i, t ->
                if (i == 0 && t.length > 1) {
                    "<p class=\"fp\"><span class=\"dc\">${t.first()}</span>${t.drop(1)}</p>"
                } else "<p>$t</p>"
            }.joinToString("\n")
    }

    val titleHtml = if (isHtml) "" else {
        // Split "第一章 惊蛰" into number + subtitle
        val parts = escapedTitle.split(Regex("\\s+"), limit = 2)
        if (parts.size == 2) {
            "<div class=\"chapter-title-block\"><div class=\"chapter-num\">${parts[0]}</div>" +
            "<div class=\"chapter-sub\">${parts[1]}</div></div>"
        } else {
            "<div class=\"chapter-title-block\"><div class=\"chapter-num\">$escapedTitle</div></div>"
        }
    }

    val fontCss = when (fontFamily) {
        "custom" -> if (customFontUri.isNotEmpty()) "'CustomFont','Noto Serif SC',serif" else "'Noto Serif SC',serif"
        "noto_serif_sc" -> "'Noto Serif SC','Noto Serif CJK SC','Source Han Serif SC','Crimson Pro','Georgia',serif"
        "noto_sans_sc" -> "'Noto Sans SC','Noto Sans CJK SC','Source Han Sans SC','Helvetica Neue',sans-serif"
        "kaiti" -> "'KaiTi','STKaiti','AR PL UKai CN','Noto Serif SC',serif"
        "fangsong" -> "'FangSong','STFangsong','Noto Serif SC',serif"
        "lxgw" -> "'LXGW WenKai','Noto Serif SC',serif"
        "crimson_pro" -> "'CrimsonPro','Crimson Pro','Georgia','Noto Serif SC',serif"
        "inter" -> "'InterFont','Inter','Helvetica Neue','Noto Sans SC',sans-serif"
        "system" -> "system-ui,-apple-system,sans-serif"
        else -> "'Noto Serif SC',serif"
    }
    // @font-face for bundled asset fonts + custom font
    val fontFaces = buildString {
        append("@font-face{font-family:'CrimsonPro';src:url('file:///android_asset/fonts/CrimsonPro.ttf');font-display:swap;}")
        append("@font-face{font-family:'CrimsonPro';src:url('file:///android_asset/fonts/CrimsonPro-Italic.ttf');font-style:italic;font-display:swap;}")
        append("@font-face{font-family:'InterFont';src:url('file:///android_asset/fonts/Inter.ttf');font-display:swap;}")
        append("@font-face{font-family:'Cormorant Garamond';src:url('file:///android_asset/fonts/CormorantGaramond.ttf');font-display:swap;}")
        if (fontFamily == "custom" && customFontUri.isNotEmpty()) {
            append("@font-face{font-family:'CustomFont';src:url('$customFontUri');font-display:swap;}")
        }
    }
    val fontSizePx = fontSize.toInt()
    val accentAlpha = accent.replace("#", "")
    val r = Integer.parseInt(accentAlpha.substring(0,2),16)
    val g = Integer.parseInt(accentAlpha.substring(2,4),16)
    val b = Integer.parseInt(accentAlpha.substring(4,6),16)
    val selBg = "rgba($r,$g,$b,0.25)"

    val bgCss = when {
        customBgImage.isNotEmpty() -> """background:$bg url('$customBgImage') center/cover fixed no-repeat;"""
        backgroundTexture == "texture:paper" -> """background:$bg;
  background-image:
    repeating-linear-gradient(0deg,transparent,transparent 2px,rgba(139,119,90,0.02) 2px,rgba(139,119,90,0.02) 4px),
    repeating-linear-gradient(90deg,transparent,transparent 3px,rgba(139,119,90,0.015) 3px,rgba(139,119,90,0.015) 6px);"""
        else -> "background:$bg;"
    }

    val isPaged = mode != PageTurnMode.SCROLL

    // For paged mode: body is the scroll container with overflow:hidden, JS paginates via window.scrollTo
    val pagedCss = if (isPaged) "" else ""

    val normalBodyCss = if (isPaged) {
        "$bgCss color:$fg;font-family:$fontCss;font-size:${fontSizePx}px;line-height:$lineHeight;" +
        "padding:${marginTop}px ${marginHorizontal}px;margin:0;" +
        "letter-spacing:0.02em;-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility;" +
        "font-feature-settings:'kern' 1,'liga' 1;word-break:break-all;overflow-wrap:break-word;"
    } else {
        "$bgCss color:$fg;font-family:$fontCss;font-size:${fontSizePx}px;line-height:$lineHeight;" +
        "letter-spacing:0.02em;padding:${marginTop}px ${marginHorizontal}px ${marginBottom + 80}px;min-height:100%;" +
        "-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility;" +
        "font-feature-settings:'kern' 1,'liga' 1;word-break:break-all;overflow-wrap:break-word;" +
        if (dualPage) "column-count:2;column-gap:40px;column-rule:1px solid ${fg}22;" else ""
    }

    // Tap handling JS — all paged modes use _goPage() for pagination
    // tapLeftAction: "prev" = left tap goes back, "next" = left tap goes forward
    val leftDir = if (tapLeftAction == "next") 1 else -1
    val rightDir = if (tapLeftAction == "next") -1 else 1
    val tapJs = when (mode) {
        PageTurnMode.SCROLL -> """
            MoRealm.onTap('center');"""
        PageTurnMode.SWIPE_LR -> """
            var x=(_touchStartX)/window.innerWidth;
            if(x<0.35){_goPage($leftDir);}
            else if(x>0.65){_goPage($rightDir);}
            else{MoRealm.onTap('center');}"""
        PageTurnMode.TAP_ZONE -> """
            var x=(_touchStartX)/window.innerWidth;
            if(x<0.3){_goPage($leftDir);}
            else if(x>0.7){_goPage($rightDir);}
            else{MoRealm.onTap('center');}"""
        PageTurnMode.FULLSCREEN -> """
            var x=(_touchStartX)/window.innerWidth;
            var y=(_touchStartY)/window.innerHeight;
            if(x<0.15&&y<0.15){_goPage(-1);}
            else if(x>0.35&&x<0.65&&y>0.35&&y<0.65){MoRealm.onTap('center');}
            else{_goPage(1);}"""
    }

    // Swipe detection JS
    val swipeJs = when (mode) {
        PageTurnMode.SWIPE_LR -> """
            if(dx<-50&&Math.abs(dy)<Math.abs(dx)*0.8){_goPage(1);return;}
            if(dx>50&&Math.abs(dy)<Math.abs(dx)*0.8){_goPage(-1);return;}"""
        else -> """
            if(dx>80&&Math.abs(dy)<dx*0.6){MoRealm.onSwipe('right');return;}"""
    }

    // Paged mode JS: paragraph-snapped pagination (no half-cut paragraphs)
    // Page animation CSS + JS
    val pageAnimCss = if (isPaged && pageAnim != "none") """
/* Page turn animation overlay */
#page-anim-overlay{position:fixed;top:0;left:0;width:100%;height:100%;pointer-events:none;z-index:50;
  background:var(--bg);opacity:0;transition:none}
""" else ""

    val pageAnimJs = if (isPaged) when (pageAnim) {
        "fade" -> """
var _animOverlay=document.getElementById('page-anim-overlay');
function _animatePage(cb){
  if(!_animOverlay){cb();return;}
  _animOverlay.style.transition='opacity 0.15s ease-in';
  _animOverlay.style.opacity='1';
  setTimeout(function(){cb();
    _animOverlay.style.transition='opacity 0.2s ease-out';
    _animOverlay.style.opacity='0';
  },160);
}"""
        "slide" -> """
function _animatePage(cb,dir){
  document.body.style.transition='transform 0.25s ease-out';
  document.body.style.transform='translateX('+(dir>0?'-30':'30')+'%)';
  setTimeout(function(){
    document.body.style.transition='none';
    document.body.style.transform='translateX('+(dir>0?'30':'-30')+'%)';
    cb();
    requestAnimationFrame(function(){
      document.body.style.transition='transform 0.25s ease-out';
      document.body.style.transform='translateX(0)';
    });
  },260);
}"""
        "cover" -> """
var _animOverlay=document.getElementById('page-anim-overlay');
function _animatePage(cb,dir){
  if(!_animOverlay){cb();return;}
  _animOverlay.style.transition='none';
  _animOverlay.style.transform='translateX('+(dir>0?'100':'-100')+'%)';
  _animOverlay.style.opacity='1';
  requestAnimationFrame(function(){
    _animOverlay.style.transition='transform 0.3s ease-out';
    _animOverlay.style.transform='translateX(0)';
  });
  setTimeout(function(){cb();
    _animOverlay.style.transition='opacity 0.15s';
    _animOverlay.style.opacity='0';
    _animOverlay.style.transform='';
  },320);
}"""
        "vertical" -> """
function _animatePage(cb,dir){
  document.body.style.transition='transform 0.25s ease-out';
  document.body.style.transform='translateY('+(dir>0?'-30':'30')+'%)';
  setTimeout(function(){
    document.body.style.transition='none';
    document.body.style.transform='translateY('+(dir>0?'30':'-30')+'%)';
    cb();
    requestAnimationFrame(function(){
      document.body.style.transition='transform 0.25s ease-out';
      document.body.style.transform='translateY(0)';
    });
  },260);
}"""
        "simulation" -> """
function _animatePage(cb,dir){
  document.body.style.transition='transform 0.3s cubic-bezier(0.2,0.8,0.3,1),opacity 0.3s';
  document.body.style.transformOrigin=dir>0?'left center':'right center';
  document.body.style.transform='perspective(1200px) rotateY('+(dir>0?'-4':'4')+'deg) scale(0.96)';
  document.body.style.opacity='0.6';
  setTimeout(function(){
    document.body.style.transition='none';
    document.body.style.transform='perspective(1200px) rotateY('+(dir>0?'4':'-4')+'deg) scale(0.96)';
    document.body.style.opacity='0.6';
    cb();
    requestAnimationFrame(function(){
      document.body.style.transition='transform 0.3s cubic-bezier(0.2,0.8,0.3,1),opacity 0.3s';
      document.body.style.transform='perspective(1200px) rotateY(0deg) scale(1)';
      document.body.style.opacity='1';
    });
  },310);
}"""
        else -> "function _animatePage(cb){cb();}"
    } else "function _animatePage(cb){cb();}"

    val paginationJs = if (isPaged) """
var _curPage=0,_totalPages=1,_startFromLast=false;
var _pageH=window.innerHeight;
var _pageBreaks=[0];
function _calcPages(){
  _pageH=window.innerHeight;
  _pageBreaks=[0];
  var els=document.body.querySelectorAll('p,.chapter-title-block,h1,h2,h3,h4,blockquote,figure,img,div,section,li,tr');
  if(els.length===0){
    var sh=document.body.scrollHeight;
    _totalPages=Math.max(1,Math.ceil(sh/_pageH));
    for(var i=1;i<_totalPages;i++) _pageBreaks.push(i*_pageH);
  }else{
    var curBreak=0;
    for(var i=0;i<els.length;i++){
      var el=els[i];
      var rect=el.getBoundingClientRect();
      var top=rect.top+window.pageYOffset;
      var bot=top+rect.height;
      // If this element's bottom exceeds the current page boundary,
      // start a new page at this element's top so it's fully visible
      if(bot>curBreak+_pageH){
        // If the element itself is taller than a page, just use its top
        // and let it overflow (unavoidable for very tall elements)
        var newBreak=Math.max(curBreak+1,top);
        _pageBreaks.push(newBreak);
        curBreak=newBreak;
        // If even after starting at this element's top, it still overflows,
        // we need additional page breaks within it
        while(bot>curBreak+_pageH){
          curBreak+=_pageH;
          _pageBreaks.push(curBreak);
        }
      }
    }
  }
  _totalPages=_pageBreaks.length;
  var pi=document.getElementById('page-indicator');
  if(pi)pi.textContent=(_curPage+1)+'/'+_totalPages;
}
function _goPage(dir){
  var next=_curPage+dir;
  if(next<0){MoRealm.onTap('prev');return;}
  if(next>=_totalPages){MoRealm.onTap('next');return;}
  _animatePage(function(){
    _curPage=next;
    var y=_pageBreaks[next]||0;
    window.scrollTo(0,y);
    _reportProgress();
    var pi=document.getElementById('page-indicator');
    if(pi)pi.textContent=(next+1)+'/'+_totalPages;
  },dir);
}
function _reportProgress(){
  var pct=_totalPages>1?Math.round(100*_curPage/(_totalPages-1)):100;
  try{MoRealm.onProgress(pct);}catch(e){}
}
function _goToLastPage(){
  _calcPages();
  if(_totalPages>1){
    _curPage=_totalPages-1;
    var y=_pageBreaks[_curPage]||0;
    window.scrollTo(0,y);
  }
  _reportProgress();
  var pi=document.getElementById('page-indicator');
  if(pi)pi.textContent=(_curPage+1)+'/'+_totalPages;
}
function _relayout(){
  if(_startFromLast){
    _startFromLast=false;
    _curPage=0;
    window.scrollTo(0,0);
    setTimeout(function(){_goToLastPage();},200);
    setTimeout(function(){_goToLastPage();},600);
    // Safety: ensure page is visible even if calculation fails
    setTimeout(function(){if(_totalPages<=1){_calcPages();_reportProgress();}},1000);
  }else{
    _curPage=0;
    window.scrollTo(0,0);
    setTimeout(function(){_calcPages();_reportProgress();},200);
  }
}
setTimeout(function(){_calcPages();_reportProgress();},300);
if(typeof ResizeObserver!=='undefined'){
  new ResizeObserver(function(){requestAnimationFrame(function(){_calcPages();_reportProgress();});}).observe(document.body);
}else{
  setTimeout(function(){_calcPages();_reportProgress();},800);
}
window.addEventListener('resize',function(){
  _pageH=window.innerHeight;_relayout();
});
document.addEventListener('load',function(e){
  if(e.target&&e.target.tagName==='IMG'){setTimeout(_calcPages,100);}
},true);
""" else """
function _relayout(){}
"""

    // Content wrapper: no extra wrappers needed — body is the container
    val contentOpen = ""
    val contentClose = ""

    return """<!DOCTYPE html><html lang="zh-CN"><head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
$fontFaces
:root{--bg:$bg;--fg:$fg;--accent:$accent}
*{margin:0;padding:0;box-sizing:border-box;-webkit-tap-highlight-color:transparent;border:none;outline:none}
table,td,th,tr{border:none!important;outline:none!important}
div,section,article,aside,nav,span{border:none!important;outline:none!important;box-shadow:none!important}
body{$normalBodyCss}
$pagedCss
/* Chapter title */
h1.ct{font-family:'Cormorant Garamond','Noto Serif SC',serif;font-size:1.35em;font-weight:$titleFontWeight;
  letter-spacing:-0.3px;color:var(--accent);margin-bottom:1.2em;padding-bottom:0.6em;
  border-bottom:1px solid rgba(128,128,128,0.15);break-after:avoid}
p{text-indent:2em;margin-bottom:${paragraphSpacing}em;text-align:justify;hyphens:auto;orphans:2;widows:2}
.fp{text-indent:0}
.dc{float:left;font-family:'Cormorant Garamond','Noto Serif SC',serif;
  font-size:3.2em;line-height:0.85;padding-right:6px;padding-top:4px;color:var(--accent);font-weight:600}
.hl{background:$selBg;border-radius:3px;padding:0 2px}
::selection{background:$selBg;color:inherit}
img{max-width:100%;min-width:70%;height:auto;display:block;margin:1.5em auto;border-radius:4px;
  object-fit:contain;break-inside:avoid}
img[src*="image"],img[src*="Image"],img[src*="illust"],img[src*="cover"]{min-width:90%}
figure{margin:1.5em 0;text-align:center;break-inside:avoid}
figure img{margin:0 auto 0.5em}
figcaption{font-size:0.85em;color:var(--fg);opacity:0.6;font-style:italic;text-indent:0;text-align:center}
img.float-left{float:left;margin:0.5em 1em 0.5em 0;max-width:45%}
img.float-right{float:right;margin:0.5em 0 0.5em 1em;max-width:45%}
svg{max-width:100%;height:auto;display:block;margin:1em auto}
.cover img{max-width:80%;margin:2em auto;border-radius:8px;box-shadow:0 4px 20px rgba(0,0,0,0.3)}
h2,h3,h4{color:var(--accent);margin:1.5em 0 0.8em;font-weight:500;letter-spacing:-0.2px;break-after:avoid}
h2{font-size:1.2em}h3{font-size:1.1em}h4{font-size:1.05em}
blockquote{border-left:3px solid rgba($r,$g,$b,0.4);padding-left:1em;margin:1em 0;font-style:italic;opacity:0.85}
.ch-end{text-align:center;padding:40px 0 20px;color:var(--accent);opacity:0.5;font-size:0.85em;clear:both}
/* Chapter divider for continuous scroll mode */
.chapter-divider{text-align:center;color:var(--accent);font-size:1.1em;font-weight:500;
  padding:2em 0 1em;margin:0;border-top:1px solid rgba(128,128,128,0.15);opacity:0.8}
.chapter-block:first-child .chapter-divider{border-top:none;padding-top:0}
/* Chapter title block — left accent bar style */
.chapter-title-block{border-left:3px solid var(--accent);padding-left:12px;margin:0 0 1.5em;
  break-after:avoid}
.chapter-block:not(:first-child) .chapter-title-block{margin-top:2.5em;padding-top:1.5em;
  border-top:1px solid rgba(128,128,128,0.1)}
.chapter-num{font-size:1.4em;font-weight:700;color:var(--fg);line-height:1.3}
.chapter-sub{font-size:1.05em;font-weight:400;color:var(--fg);opacity:0.7;margin-top:2px}
/* Page indicator for paged mode */
#page-indicator{position:fixed;bottom:8px;right:12px;font-size:11px;color:var(--fg);opacity:0.35;
  pointer-events:none;z-index:100;display:${if (isPaged) "block" else "none"}}
/* Reader status bar (bottom) */
#reader-status{position:fixed;bottom:0;left:0;right:0;display:flex;justify-content:space-between;
  padding:4px 12px;font-size:10px;color:var(--fg);opacity:0.3;pointer-events:none;z-index:99;
  font-family:system-ui,sans-serif}
#reader-status .left{max-width:60%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
/* Text selection action bar */
#sel-bar{display:none;position:fixed;top:0;left:50%;transform:translateX(-50%);
  background:rgba(0,0,0,0.85);border-radius:20px;padding:4px 8px;z-index:200;
  flex-direction:row;gap:4px;align-items:center}
#sel-bar.show{display:flex}
#sel-bar button{background:none;border:none;color:#fff;font-size:13px;padding:6px 12px;
  border-radius:16px;cursor:pointer}
#sel-bar button:active{background:rgba(255,255,255,0.2)}
$pageAnimCss
${customCss}
</style></head><body>
$contentOpen
$titleHtml
$escapedContent
$contentClose
<div id="page-indicator"></div>
<div id="reader-status">${if (showChapterName) "<span class=\"left\">$escapedTitle</span>" else "<span></span>"}${if (showTimeBattery) "<span id=\"status-time\"></span>" else ""}</div>
<div id="sel-bar"><button onclick="_selCopy()">复制</button><button onclick="_selShare()">分享</button><button onclick="_selSpeak()">朗读</button></div>
${if (isPaged && (pageAnim == "fade" || pageAnim == "cover")) """<div id="page-anim-overlay"></div>""" else ""}
<script>
${if (showTimeBattery) """
function _updateTime(){
  var d=new Date();
  var h=d.getHours().toString().padStart(2,'0');
  var m=d.getMinutes().toString().padStart(2,'0');
  var el=document.getElementById('status-time');
  if(el)el.textContent=h+':'+m;
}
_updateTime();setInterval(_updateTime,30000);
""" else ""}
var _lp=null,_longFired=false,_touchStartX=0,_touchStartY=0,_touchMoved=false;
document.addEventListener('touchstart',function(e){
  _touchStartX=e.touches[0].clientX;_touchStartY=e.touches[0].clientY;
  _touchMoved=false;_longFired=false;
  _lp=setTimeout(function(){_longFired=true;MoRealm.onLong()},600);
},{passive:true});
document.addEventListener('touchmove',function(e){
  var dx=Math.abs(e.touches[0].clientX-_touchStartX);
  var dy=Math.abs(e.touches[0].clientY-_touchStartY);
  if(dx>10||dy>10){_touchMoved=true;clearTimeout(_lp);}
  ${if (isPaged) "e.preventDefault();" else ""}
},${if (isPaged) "{passive:false}" else "{passive:true}"});
document.addEventListener('touchend',function(e){
  clearTimeout(_lp);if(_longFired)return;
  var dx=e.changedTouches[0].clientX-_touchStartX;
  var dy=e.changedTouches[0].clientY-_touchStartY;
  $swipeJs
  if(!_touchMoved){$tapJs}
});
// Scroll progress + continuous scroll (画卷模式)
var _scrollTimer=null,_bottomHit=false,_userScrolled=false,_nearBottomFired=false;
function _checkScroll(){
  var sh=document.documentElement.scrollHeight||document.body.scrollHeight;
  var st=window.pageYOffset||document.documentElement.scrollTop;
  var vh=window.innerHeight;
  var pct=sh>vh?Math.min(100,Math.round(100*st/(sh-vh))):100;
  try{MoRealm.onProgress(pct);}catch(e){}
  // Continuous scroll: request more content when 80% scrolled
  if(pct>80&&!_nearBottomFired){
    _nearBottomFired=true;
    try{MoRealm.onNearBottom();}catch(e){}
  }
  if(pct<70){_nearBottomFired=false;}
  // Track visible chapter by checking chapter-block elements
  var blocks=document.querySelectorAll('.chapter-block');
  if(blocks.length>0){
    for(var i=blocks.length-1;i>=0;i--){
      if(blocks[i].getBoundingClientRect().top<=vh*0.3){
        var idx=parseInt(blocks[i].getAttribute('data-index'));
        if(!isNaN(idx)){try{MoRealm.onChapterVisible(idx);}catch(e){}}
        break;
      }
    }
  }
}
window.addEventListener('scroll',function(){
  _userScrolled=true;
  clearTimeout(_scrollTimer);
  _scrollTimer=setTimeout(_checkScroll,150);
},{passive:true});
setTimeout(function(){
  var sh=document.documentElement.scrollHeight||document.body.scrollHeight;
  if(sh<=window.innerHeight+5){try{MoRealm.onProgress(100);}catch(e){}}
},500);
$pageAnimJs
$paginationJs
// Text selection handling
document.addEventListener('selectionchange',function(){
  var sel=window.getSelection();
  var text=sel?sel.toString().trim():'';
  var bar=document.getElementById('sel-bar');
  if(text.length>0){
    bar.classList.add('show');
    try{MoRealm.onTextSelected(text);}catch(e){}
    // Position bar above selection
    if(sel.rangeCount>0){
      var r=sel.getRangeAt(0).getBoundingClientRect();
      bar.style.top=Math.max(8,r.top-44)+'px';
    }
  }else{
    bar.classList.remove('show');
  }
});
function _selCopy(){
  var t=window.getSelection().toString();
  if(t){
    var ta=document.createElement('textarea');ta.value=t;
    document.body.appendChild(ta);ta.select();document.execCommand('copy');
    document.body.removeChild(ta);
    window.getSelection().removeAllRanges();
  }
}
function _selShare(){
  var t=window.getSelection().toString();
  if(t){try{MoRealm.onTextSelected(t);}catch(e){}}
  window.getSelection().removeAllRanges();
}
function _selSpeak(){
  var t=window.getSelection().toString();
  if(t){try{MoRealm.onSpeakSelected(t);}catch(e){}}
  window.getSelection().removeAllRanges();
}
// Image click handling
document.addEventListener('click',function(e){
  var el=e.target;
  if(el.tagName==='IMG'&&el.src){
    e.preventDefault();e.stopPropagation();
    try{MoRealm.onImageClick(el.src);}catch(ex){}
  }
},true);
</script></body></html>""".trimIndent()
}

// ── Top Bar ─────────────────────────────────────────────

@Composable
fun ReaderTopBar(bookTitle: String, onBack: () -> Unit, onExport: () -> Unit = {}, onBookmark: () -> Unit = {}) {
    val moColors = LocalMoRealmColors.current
    var showMenu by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = moColors.bottomBar.copy(alpha = 0.95f),
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回",
                    tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                bookTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onBookmark) {
                Icon(Icons.Default.BookmarkAdd, "书签",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp))
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "更多",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("导出为 TXT") },
                        leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                        onClick = { showMenu = false; onExport() },
                    )
                }
            }
        }
    }
}

// ── Bottom Control Bar (HTML prototype style: floating pill) ──

@Composable
fun ReaderControlBar(
    currentChapter: Int, totalChapters: Int, chapterTitle: String,
    scrollProgress: Int = 0,
    onBack: () -> Unit, onPrevChapter: () -> Unit, onNextChapter: () -> Unit,
    onTts: () -> Unit, onSettings: () -> Unit, onChapterSelect: () -> Unit,
    onSearch: () -> Unit = {},
    onAutoPage: () -> Unit = {},
) {
    val moColors = LocalMoRealmColors.current
    // Combine chapter progress with scroll progress for a smooth overall %
    val chapterFraction = if (totalChapters > 0) currentChapter.toFloat() / totalChapters else 0f
    val scrollFraction = if (totalChapters > 0) scrollProgress / 100f / totalChapters else 0f
    val progress = (chapterFraction + scrollFraction).coerceIn(0f, 1f)
    val progressPct = (progress * 100).toInt()

    // Floating pill bar like HTML prototype's .r-bar
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .navigationBarsPadding(),
        color = moColors.bottomBar.copy(alpha = 0.88f),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 8.dp,
        shadowElevation = 10.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            // Progress info + track (like HTML: 第23章 · 35% + thin bar)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onChapterSelect, modifier = Modifier.size(32.dp)) {
                    @Suppress("DEPRECATION")
                    Icon(Icons.Default.FormatListBulleted, "目录",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onSearch, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Search, "搜索",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp))
                }
                // Center: progress text + bar
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "第${currentChapter + 1}章 · ${progressPct}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = moColors.accent,
                        trackColor = moColors.accent.copy(alpha = 0.15f),
                    )
                }
                IconButton(onClick = onTts, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.RecordVoiceOver, "朗读",
                        tint = moColors.accent,
                        modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onAutoPage, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Timer, "自动翻页",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onSettings, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.TextFields, "设置",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp))
                }
            }
            // Chapter nav row
            if (totalChapters > 1) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TextButton(onClick = onPrevChapter) {
                        Text("上一章", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Text(
                        chapterTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            .align(Alignment.CenterVertically),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    TextButton(onClick = onNextChapter) {
                        Text("下一章", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

// ── Settings Panel (font, page turn mode) ───────────────

@Composable
fun ReaderSettingsPanel(
    currentMode: PageTurnMode,
    onModeChange: (PageTurnMode) -> Unit,
    currentFont: String = "noto_serif_sc",
    onFontChange: (String) -> Unit = {},
    currentFontSize: Float = 17f,
    onFontSizeChange: (Float) -> Unit = {},
    currentLineHeight: Float = 2.0f,
    onLineHeightChange: (Float) -> Unit = {},
    customFontName: String = "",
    onImportFont: (android.net.Uri, String) -> Unit = { _, _ -> },
    onClearCustomFont: () -> Unit = {},
    allThemes: List<ThemeEntity> = emptyList(),
    activeThemeId: String = "",
    onThemeChange: (String) -> Unit = {},
    brightness: Float = -1f,
    onBrightnessChange: (Float) -> Unit = {},
    paragraphSpacing: Float = 1.4f,
    onParagraphSpacingChange: (Float) -> Unit = {},
    marginHorizontal: Int = 24,
    onMarginHorizontalChange: (Int) -> Unit = {},
    marginTop: Int = 24,
    onMarginTopChange: (Int) -> Unit = {},
    marginBottom: Int = 24,
    onMarginBottomChange: (Int) -> Unit = {},
    customCss: String = "",
    onCustomCssChange: (String) -> Unit = {},
    customBgImage: String = "",
    onCustomBgImageChange: (String) -> Unit = {},
    readerStyles: List<com.morealm.app.domain.entity.ReaderStyle> = emptyList(),
    activeStyleId: String = "",
    onStyleChange: (String) -> Unit = {},
    screenOrientation: Int = -1,
    onScreenOrientationChange: (Int) -> Unit = {},
    textSelectable: Boolean = true,
    onTextSelectableChange: (Boolean) -> Unit = {},
    chineseConvertMode: Int = 0,
    onChineseConvertModeChange: (Int) -> Unit = {},
    readerEngine: String = "canvas",
    onReaderEngineChange: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val moColors = LocalMoRealmColors.current
    var fontSize by remember { mutableFloatStateOf(currentFontSize) }
    var lineHeight by remember { mutableFloatStateOf(currentLineHeight) }
    var selectedFont by remember { mutableStateOf(currentFont) }

    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
        color = moColors.bottomBar.copy(alpha = 0.97f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 12.dp,
    ) {
        Column(modifier = Modifier
            .navigationBarsPadding()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
        ) {
            // Drag handle
            Box(Modifier.width(40.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                .align(Alignment.CenterHorizontally))

            Spacer(Modifier.height(16.dp))

            // ── Reader Style Presets ──
            if (readerStyles.isNotEmpty()) {
                Text("阅读样式", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    readerStyles.forEach { style ->
                        val isActive = style.id == activeStyleId
                        val bg = style.bgColor.toComposeColor()
                        val fg = style.textColor.toComposeColor()
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { onStyleChange(style.id) },
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(bg)
                                    .then(
                                        if (isActive) Modifier.border(2.dp, moColors.accent, CircleShape)
                                        else Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f), CircleShape)
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("文", color = fg,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(style.name,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = if (isActive) moColors.accent
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Brightness ──
            Text("亮度", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            var brightnessVal by remember { mutableFloatStateOf(brightness) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BrightnessLow, null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp))
                Slider(
                    value = if (brightnessVal < 0f) 0.5f else brightnessVal,
                    onValueChange = { brightnessVal = it; onBrightnessChange(it) },
                    valueRange = 0.01f..1f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = moColors.accent, activeTrackColor = moColors.accent),
                )
                Icon(Icons.Default.BrightnessHigh, null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = brightnessVal < 0f,
                    onClick = {
                        brightnessVal = -1f
                        onBrightnessChange(-1f)
                    },
                    label = { Text("跟随系统") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = moColors.accent.copy(alpha = 0.2f),
                        selectedLabelColor = moColors.accent),
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Font size ──
            Text("字号", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Slider(
                    value = fontSize, onValueChange = { fontSize = it; onFontSizeChange(it) },
                    valueRange = 12f..100f, steps = 0,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = moColors.accent, activeTrackColor = moColors.accent),
                )
                Text("A", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
            }
            Text("${fontSize.toInt()}px" + if (fontSize > 50f) " ⚠ 超大字号可能影响排版" else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (fontSize > 50f) moColors.accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))

            Spacer(Modifier.height(12.dp))

            // ── Font family ──
            Text("字体", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Spacer(Modifier.height(6.dp))
            val context = LocalContext.current
            val fontPickerLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                uri?.let {
                    val name = DocumentFile.fromSingleUri(context, it)?.name
                        ?.substringBeforeLast('.') ?: "自定义字体"
                    onImportFont(it, name)
                    selectedFont = "custom"
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                data class FontOption(val key: String, val label: String)
                val builtinFonts = listOf(
                    FontOption("noto_serif_sc", "宋体"),
                    FontOption("noto_sans_sc", "黑体"),
                    FontOption("kaiti", "楷体"),
                    FontOption("fangsong", "仿宋"),
                )
                builtinFonts.forEach { font ->
                    FilterChip(
                        selected = selectedFont == font.key,
                        onClick = { selectedFont = font.key; onFontChange(font.key) },
                        label = { Text(font.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = moColors.accent.copy(alpha = 0.2f),
                            selectedLabelColor = moColors.accent),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                listOf("crimson_pro" to "Crimson", "inter" to "Inter", "system" to "系统").forEach { (key, label) ->
                    FilterChip(
                        selected = selectedFont == key,
                        onClick = { selectedFont = key; onFontChange(key) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = moColors.accent.copy(alpha = 0.2f),
                            selectedLabelColor = moColors.accent),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (customFontName.isNotEmpty()) {
                    FilterChip(
                        selected = selectedFont == "custom",
                        onClick = { selectedFont = "custom"; onFontChange("custom") },
                        label = { Text(customFontName) },
                        trailingIcon = {
                            Icon(Icons.Default.Close, "清除",
                                modifier = Modifier.size(14.dp)
                                    .clickable { onClearCustomFont(); selectedFont = "noto_serif_sc" })
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = moColors.accent.copy(alpha = 0.2f),
                            selectedLabelColor = moColors.accent),
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = { fontPickerLauncher.launch(arrayOf("font/*", "application/octet-stream")) },
                    label = { Text("导入字体") },
                    leadingIcon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = moColors.accent.copy(alpha = 0.08f)),
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Line height ──
            Text("行距", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1.5f to "紧凑", 1.8f to "适中", 2.0f to "宽松", 2.4f to "超宽").forEach { (v, l) ->
                    FilterChip(
                        selected = lineHeight == v,
                        onClick = { lineHeight = v; onLineHeightChange(v) },
                        label = { Text(l) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = moColors.accent.copy(alpha = 0.2f),
                            selectedLabelColor = moColors.accent),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Paragraph spacing ──
            var paraSpace by remember { mutableFloatStateOf(paragraphSpacing) }
            Text("段间距", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f to "紧凑", 1.0f to "适中", 1.4f to "宽松", 2.0f to "超宽").forEach { (v, l) ->
                    FilterChip(
                        selected = paraSpace == v,
                        onClick = { paraSpace = v; onParagraphSpacingChange(v) },
                        label = { Text(l) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = moColors.accent.copy(alpha = 0.2f),
                            selectedLabelColor = moColors.accent),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Margins ──
            var mH by remember { mutableIntStateOf(marginHorizontal) }
            var mT by remember { mutableIntStateOf(marginTop) }
            var mB by remember { mutableIntStateOf(marginBottom) }
            Text("页边距", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("左右", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.width(32.dp))
                Slider(
                    value = mH.toFloat(), onValueChange = { mH = it.toInt(); onMarginHorizontalChange(it.toInt()) },
                    valueRange = 8f..64f, steps = 0,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = moColors.accent, activeTrackColor = moColors.accent),
                )
                Text("${mH}px", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.width(36.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("上", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.width(32.dp))
                Slider(
                    value = mT.toFloat(), onValueChange = { mT = it.toInt(); onMarginTopChange(it.toInt()) },
                    valueRange = 8f..64f, steps = 0,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = moColors.accent, activeTrackColor = moColors.accent),
                )
                Text("${mT}px", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.width(36.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("下", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.width(32.dp))
                Slider(
                    value = mB.toFloat(), onValueChange = { mB = it.toInt(); onMarginBottomChange(it.toInt()) },
                    valueRange = 8f..64f, steps = 0,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = moColors.accent, activeTrackColor = moColors.accent),
                )
                Text("${mB}px", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.width(36.dp))
            }

            Spacer(Modifier.height(16.dp))

            // ── Theme ──
            if (allThemes.isNotEmpty()) {
                Text("主题", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    allThemes.forEach { theme ->
                        val isActive = theme.id == activeThemeId
                        val bgColor = theme.readerBackground.toComposeColor()
                        val fgColor = theme.readerTextColor.toComposeColor()
                        val acColor = theme.accentColor.toComposeColor()
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(bgColor)
                                .then(
                                    if (isActive) Modifier.background(
                                        androidx.compose.ui.graphics.Color.Transparent
                                    ) else Modifier
                                )
                                .clickable { onThemeChange(theme.id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "文",
                                style = MaterialTheme.typography.labelSmall,
                                color = fgColor,
                                fontWeight = FontWeight.Bold,
                            )
                            if (isActive) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .background(acColor, RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    allThemes.find { it.id == activeThemeId }?.name ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Custom background image ──
            val bgImageLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                uri?.let {
                    // Take persistable permission
                    try {
                        val ctx = context
                        ctx.contentResolver.takePersistableUriPermission(
                            it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {}
                    onCustomBgImageChange(it.toString())
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("背景图片", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                if (customBgImage.isNotEmpty()) {
                    FilterChip(
                        selected = false,
                        onClick = { onCustomBgImageChange("") },
                        label = { Text("清除") },
                    )
                    Spacer(Modifier.width(8.dp))
                }
                FilterChip(
                    selected = customBgImage.isNotEmpty(),
                    onClick = { bgImageLauncher.launch(arrayOf("image/*")) },
                    label = { Text(if (customBgImage.isNotEmpty()) "更换" else "选择图片") },
                    leadingIcon = { Icon(Icons.Default.Image, null, modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = moColors.accent.copy(alpha = 0.2f),
                        selectedLabelColor = moColors.accent),
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Page turn mode ──
            Text("翻页方式", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PageTurnMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = currentMode == mode,
                            onClick = { onModeChange(mode) },
                            colors = RadioButtonDefaults.colors(selectedColor = moColors.accent),
                        )
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text(mode.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                            if (mode == PageTurnMode.FULLSCREEN) {
                                Text("长按呼出菜单 · 右滑返回 · 左上角点击上一章",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Screen orientation ──
            Text("屏幕方向", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-1 to "自动", 0 to "竖屏", 1 to "横屏").forEach { (v, l) ->
                    FilterChip(
                        selected = screenOrientation == v,
                        onClick = { onScreenOrientationChange(v) },
                        label = { Text(l) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = moColors.accent.copy(alpha = 0.2f),
                            selectedLabelColor = moColors.accent),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Text selectable ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("文字可选择", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = textSelectable,
                    onCheckedChange = onTextSelectableChange,
                    colors = SwitchDefaults.colors(checkedTrackColor = moColors.accent),
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Chinese conversion ──
            Text("繁简转换", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "关闭", 1 to "简→繁", 2 to "繁→简").forEach { (v, l) ->
                    FilterChip(
                        selected = chineseConvertMode == v,
                        onClick = { onChineseConvertModeChange(v) },
                        label = { Text(l) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = moColors.accent.copy(alpha = 0.2f),
                            selectedLabelColor = moColors.accent),
                    )
                }
            }

            // ── Rendering engine ──
            Spacer(Modifier.height(8.dp))
            Text("渲染引擎", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("canvas" to "Canvas (快速)", "native" to "原生文本", "webview" to "WebView").forEach { (v, l) ->
                    FilterChip(
                        selected = readerEngine == v,
                        onClick = { onReaderEngineChange(v) },
                        label = { Text(l) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = moColors.accent.copy(alpha = 0.2f),
                            selectedLabelColor = moColors.accent),
                    )
                }
            }

            // ── Custom CSS ──
            Spacer(Modifier.height(16.dp))
            var showCssEditor by remember { mutableStateOf(false) }
            var cssText by remember { mutableStateOf(customCss) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { showCssEditor = !showCssEditor },
            ) {
                Text("自定义 CSS", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                if (customCss.isNotEmpty()) {
                    Text("已配置", style = MaterialTheme.typography.labelSmall,
                        color = moColors.accent)
                }
                Icon(
                    if (showCssEditor) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp))
            }
            if (showCssEditor) {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = cssText,
                    onValueChange = { cssText = it },
                    placeholder = { Text("p { text-indent: 0; }", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = moColors.accent, cursorColor = moColors.accent),
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = false,
                        onClick = { onCustomCssChange(cssText) },
                        label = { Text("应用") },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = moColors.accent.copy(alpha = 0.15f)),
                    )
                    if (cssText.isNotEmpty()) {
                        FilterChip(
                            selected = false,
                            onClick = { cssText = ""; onCustomCssChange("") },
                            label = { Text("清除") },
                        )
                    }
                }
            }
        }
    }
}

// ── Image Viewer Dialog ──────────────────────────────────

@Composable
fun ImageViewerDialog(
    imageSrc: String,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            // Zoomable image via WebView (handles pinch-zoom natively)
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        @Suppress("DEPRECATION")
                        settings.allowFileAccess = true
                        @Suppress("DEPRECATION")
                        settings.allowContentAccess = true
                        @Suppress("DEPRECATION")
                        settings.allowFileAccessFromFileURLs = true
                        setBackgroundColor(android.graphics.Color.BLACK)
                        loadDataWithBaseURL(
                            "file:///android_asset/",
                            """<!DOCTYPE html><html><head>
                            <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=10,user-scalable=yes">
                            <style>*{margin:0;padding:0}body{background:#000;display:flex;align-items:center;justify-content:center;min-height:100vh}
                            img{max-width:100%;max-height:100vh;object-fit:contain}</style>
                            </head><body><img src="$imageSrc"></body></html>""",
                            "text/html", "UTF-8", null
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Close button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    .statusBarsPadding(),
            ) {
                Icon(
                    Icons.Default.Close, "关闭",
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
