package com.example.webview

/**
 * Distraction-Free CSS injector for WebView.
 *
 * Injects rules to eliminate clutter, sidebars, feeds, floating recommendation widgets,
 * and algorithm rabbit-holes, keeping the focus squarely on the single shared article/post/video.
 *
 * Known limitation:
 * Facebook and Instagram actively detect WebView traffic and often force a login wall or
 * app redirect regardless of CSS injection or user-agent spoofing; treat these as best-effort,
 * not guaranteed. YouTube (via embed URLs) and most other public post links should work reliably.
 */
object DistractionFreeCss {

    // General selector rules to hide navigation bars, footers, feeds, sidebars, and ads
    private const val GENERIC_HIDE_RULES = """
        header, footer, nav, aside,
        [role="navigation"], [role="banner"],
        [aria-label*="sidebar" i], [aria-label*="recommend" i],
        [aria-label*="trending" i], [aria-label*="who to follow" i],
        .sidebar, .related, .feed, .recommended,
        #comments, .comments, #sidebar, .nav-bar, .navbar,
        .social-share, .share-buttons, .promoted, .ad, .advertisement,
        [id*="google_ads"], .cookie-banner, .consent-banner,
        #cookie-banner, .newsletter-signup, .subscribe-card,
        .floating-action-bar, .sticky-footer, .infinite-scroll-trigger,
        .more-stories, .popular-posts, .taboola, .outbrain {
            display: none !important;
            visibility: hidden !important;
            height: 0 !important;
            min-height: 0 !important;
            max-height: 0 !important;
            overflow: hidden !important;
        }
        body {
            overflow-x: hidden !important;
            margin-bottom: 24px !important;
        }
    """

    // Specific rules for Twitter / X
    private const val TWITTER_RULES = """
        [data-testid="sidebarColumn"],
        [data-testid="BottomBar"],
        [aria-label="Trending"],
        [aria-label*="Who to follow" i],
        [data-testid="trend"],
        header[role="banner"],
        div[data-testid="placementTracking"],
        div[data-testid="sheetDialog"] {
            display: none !important;
        }
    """

    // Specific rules for Reddit
    private const val REDDIT_RULES = """
        shreddit-feed,
        #right-rail,
        [data-testid="search-feed"],
        #bottom-bar,
        reddit-sidebar,
        .sidebar-grid,
        shreddit-async-loader[bundlename="bottom_bar"],
        shreddit-comment-tree {
            max-width: 100% !important;
        }
    """

    // Specific rules for YouTube (fallback when viewing desktop/mobile page instead of embed)
    private const val YOUTUBE_RULES = """
        #related, #secondary, #below,
        ytd-watch-next-secondary-results-renderer,
        #comments, #masthead, ytd-browse, ytd-miniplayer,
        .ytp-endscreen-content, .ytp-ce-element,
        .ytp-cards-teaser, .ytp-pause-overlay {
            display: none !important;
        }
        ytd-watch-flexy {
            padding: 0 !important;
            margin: 0 !important;
        }
    """

    /**
     * Builds JavaScript code that creates or updates a <style> tag containing our distraction-free rules.
     */
    fun buildInjectionScript(host: String): String {
        val siteSpecific = when {
            host.contains("twitter.com") || host.contains("x.com") -> TWITTER_RULES
            host.contains("reddit.com") -> REDDIT_RULES
            host.contains("youtube.com") || host.contains("youtu.be") -> YOUTUBE_RULES
            else -> ""
        }

        val fullCss = (GENERIC_HIDE_RULES + siteSpecific)
            .replace("\n", " ")
            .replace("'", "\\'")

        return """
            (function() {
                var existing = document.getElementById('distraction-free-injected-style');
                if (existing) {
                    existing.textContent = '$fullCss';
                    return;
                }
                var style = document.createElement('style');
                style.id = 'distraction-free-injected-style';
                style.type = 'text/css';
                style.textContent = '$fullCss';
                (document.head || document.documentElement).appendChild(style);
            })();
        """.trimIndent()
    }
}
