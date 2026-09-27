package com.example.util

import android.net.Uri
import java.util.regex.Pattern

/**
 * Data class containing information about a preprocessed URL.
 *
 * @property rawInput The original raw string entered or received from intent.
 * @property targetUrl The cleaned/rewritten URL ready to be loaded by WebView.
 * @property originalExtractedUrl The pure URL extracted before rewriting.
 * @property isYouTube Whether this URL represents a YouTube video/short.
 * @property allowedHost The primary domain/host allowed for navigation isolation.
 * @property platformLabel Human-readable platform name (e.g., "YouTube Embed", "Twitter / X", "Web Article").
 */
data class ProcessedUrl(
    val rawInput: String,
    val targetUrl: String,
    val originalExtractedUrl: String,
    val isYouTube: Boolean,
    val allowedHost: String,
    val platformLabel: String
)

/**
 * URL Preprocessor for DistractionFreeViewer.
 *
 * Preprocessing tasks:
 * 1. Extract URL substring from noisy shared text (e.g. "Check this out: https://youtu.be/...").
 * 2. Rewrite YouTube links (watch, youtu.be, shorts) into an isolated embed form:
 *    https://www.youtube.com/embed/VIDEO_ID?autoplay=1&rel=0&modestbranding=1
 *    This loads a stripped player with no homepage, sidebar, or algorithmic recommendations.
 * 3. Normalize other domains (scheme assurance, tracking parameter cleanup).
 *
 * Known limitation:
 * Facebook and Instagram actively detect WebView traffic and often force a login wall or
 * app redirect regardless of CSS injection or user-agent spoofing; treat these as best-effort,
 * not guaranteed. YouTube (via embed URLs) and most other public post links should work reliably.
 */
object UrlPreprocessor {

    // Regex pattern to extract web URLs from arbitrary text
    private val URL_REGEX = Pattern.compile(
        "https?://[a-zA-Z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+",
        Pattern.CASE_INSENSITIVE
    )

    // YouTube video ID pattern (typically 11 characters alphanumeric plus - and _)
    private val YOUTUBE_VIDEO_ID_REGEX = Pattern.compile(
        "^[a-zA-Z0-9_-]{11}$"
    )

    /**
     * Extracts the first URL found in the input string.
     * If no URL pattern is found, returns the trimmed input if it looks like a domain, or null.
     */
    fun extractUrlFromText(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val matcher = URL_REGEX.matcher(trimmed)
        if (matcher.find()) {
            var url = matcher.group()
            // Clean any trailing punctuation that might have been part of sentence structure
            url = url.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
            return url
        }

        // If user typed domain like "youtube.com/watch?v=..." without scheme
        if (trimmed.contains(".") && !trimmed.contains(" ") && !trimmed.startsWith("http")) {
            return "https://$trimmed"
        }

        return null
    }

    /**
     * Preprocesses an input URL or shared message into a Distraction-Free URL.
     */
    fun process(input: String): ProcessedUrl? {
        val extracted = extractUrlFromText(input) ?: return null

        val uri = try {
            Uri.parse(extracted)
        } catch (_: Exception) {
            return null
        }

        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()

        // 1. YouTube link handling
        val youtubeVideoId = extractYouTubeVideoId(uri, host, path)
        if (youtubeVideoId != null) {
            val startSeconds = extractStartTimeSeconds(uri)
            val startParam = if (startSeconds != null && startSeconds > 0) "&start=$startSeconds" else ""

            // Embed player parameters:
            // rel=0: do not show related videos from other channels
            // modestbranding=1: minimal YouTube branding
            // playsinline=1: inline playback
            // iv_load_policy=3: disable video annotations
            val embedUrl = "https://www.youtube.com/embed/$youtubeVideoId?autoplay=1&rel=0&modestbranding=1&playsinline=1&iv_load_policy=3$startParam"

            return ProcessedUrl(
                rawInput = input,
                targetUrl = embedUrl,
                originalExtractedUrl = extracted,
                isYouTube = true,
                allowedHost = "youtube.com",
                platformLabel = "YouTube (Isolated Player)"
            )
        }

        // 2. Platform identification and normalization for other domains
        val platformLabel = when {
            host.contains("twitter.com") || host.contains("x.com") -> "Twitter / X Post"
            host.contains("reddit.com") -> "Reddit Post"
            host.contains("facebook.com") || host.contains("fb.watch") -> "Facebook Post (Best-effort)"
            host.contains("instagram.com") -> "Instagram Post (Best-effort)"
            host.contains("threads.net") -> "Threads Post"
            host.contains("tiktok.com") -> "TikTok Video"
            host.contains("linkedin.com") -> "LinkedIn Post"
            host.contains("medium.com") || host.contains("substack.com") -> "Article"
            host.isNotEmpty() -> host.removePrefix("www.")
            else -> "Web Content"
        }

        val finalUrl = if (!extracted.startsWith("http://") && !extracted.startsWith("https://")) {
            "https://$extracted"
        } else {
            extracted
        }

        return ProcessedUrl(
            rawInput = input,
            targetUrl = finalUrl,
            originalExtractedUrl = extracted,
            isYouTube = false,
            allowedHost = host.ifEmpty { "unknown" },
            platformLabel = platformLabel
        )
    }

    /**
     * Extracts YouTube Video ID from various YouTube URL formats:
     * - https://www.youtube.com/watch?v=VIDEO_ID
     * - https://m.youtube.com/watch?v=VIDEO_ID
     * - https://youtu.be/VIDEO_ID
     * - https://www.youtube.com/shorts/VIDEO_ID
     * - https://www.youtube.com/embed/VIDEO_ID
     * - https://music.youtube.com/watch?v=VIDEO_ID
     */
    private fun extractYouTubeVideoId(uri: Uri, host: String, path: String): String? {
        val isYouTubeDomain = host.contains("youtube.com") ||
                host.contains("youtube-nocookie.com") ||
                host.contains("youtu.be")

        if (!isYouTubeDomain) return null

        // Format: youtu.be/VIDEO_ID
        if (host.contains("youtu.be")) {
            val segments = uri.pathSegments
            if (segments.isNotEmpty()) {
                val candidate = segments[0]
                if (isValidVideoId(candidate)) return candidate
            }
        }

        // Format: youtube.com/watch?v=VIDEO_ID
        val vParam = uri.getQueryParameter("v")
        if (!vParam.isNullOrEmpty() && isValidVideoId(vParam)) {
            return vParam
        }

        // Format: youtube.com/shorts/VIDEO_ID or youtube.com/embed/VIDEO_ID
        val segments = uri.pathSegments
        for (i in 0 until segments.size - 1) {
            val segment = segments[i].lowercase()
            if (segment == "shorts" || segment == "embed" || segment == "v") {
                val candidate = segments[i + 1]
                if (isValidVideoId(candidate)) return candidate
            }
        }

        // Check if path is /shorts/VIDEO_ID directly
        if (path.startsWith("/shorts/")) {
            val candidate = path.removePrefix("/shorts/").substringBefore("/").substringBefore("?")
            if (isValidVideoId(candidate)) return candidate
        }

        return null
    }

    private fun isValidVideoId(id: String): Boolean {
        return YOUTUBE_VIDEO_ID_REGEX.matcher(id).matches()
    }

    /**
     * Extracts optional start time parameter (e.g. t=1m30s, t=90, or start=90).
     */
    private fun extractStartTimeSeconds(uri: Uri): Long? {
        val startParam = uri.getQueryParameter("start")
        if (!startParam.isNullOrEmpty()) {
            return startParam.toLongOrNull()
        }

        val tParam = uri.getQueryParameter("t") ?: return null
        // t could be "120" or "2m10s" or "1h2m3s"
        return parseTimeString(tParam)
    }

    private fun parseTimeString(time: String): Long? {
        time.toLongOrNull()?.let { return it }

        var totalSeconds = 0L
        var currentNumber = ""
        for (char in time) {
            if (char.isDigit()) {
                currentNumber += char
            } else {
                val num = currentNumber.toLongOrNull() ?: 0L
                when (char.lowercaseChar()) {
                    'h' -> totalSeconds += num * 3600
                    'm' -> totalSeconds += num * 60
                    's' -> totalSeconds += num
                }
                currentNumber = ""
            }
        }
        return if (totalSeconds > 0) totalSeconds else null
    }
}
