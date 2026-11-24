package dev.howard.colorosenhance.updates;

import java.net.URI;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Accept published releases with an APK from the same repository as the selected feed. */
public record ReleaseInfo(long versionCode, String versionName, String notes, String notesHtml, String pageUrl,
                          String apkUrl, boolean prerelease) {
    public static final String SOURCE_URL = "https://github.com/Howard-Tse/ColorOS-Enhance";
    public static final String MODULE_URL = "https://github.com/Xposed-Modules-Repo/io.github.howard_tse.colorosenhance";
    public static final String RELEASES_URL = MODULE_URL + "/releases";
    public static final String SOURCE_RELEASES_URL = SOURCE_URL + "/releases";
    public static final String SOURCE_RELEASES_API_URL = "https://api.github.com/repos/Howard-Tse/ColorOS-Enhance/releases";
    public static final String API_URL = "https://api.github.com/repos/Xposed-Modules-Repo/io.github.howard_tse.colorosenhance/releases/latest";
    private static final Pattern TAG = Pattern.compile("([1-9][0-9]{0,9})-([0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?)");

    public boolean newerThan(long installedCode) { return versionCode > installedCode; }
    public boolean hasNotes() { return !notes.isBlank() || !notesHtml.isBlank(); }

    public static ReleaseInfo parse(String json) throws JSONException {
        return parse(new JSONObject(json), false);
    }

    static ReleaseInfo latestListed(String json) throws JSONException {
        JSONArray releases = new JSONArray(json);
        ReleaseInfo latest = null;
        for (int i = 0; i < releases.length(); i++) {
            try {
                ReleaseInfo candidate = parse(releases.getJSONObject(i), true);
                if (latest == null || candidate.newerThan(latest.versionCode())
                        || candidate.versionCode() == latest.versionCode() && latest.prerelease() && !candidate.prerelease()) {
                    latest = candidate;
                }
            } catch (JSONException unavailable) {
                // A draft or a release whose APK is unavailable must not hide valid updates.
            }
        }
        return latest;
    }

    private static ReleaseInfo parse(JSONObject release, boolean includePrereleases) throws JSONException {
        boolean prerelease = release.getBoolean("prerelease");
        if (release.getBoolean("draft") || prerelease && !includePrereleases)
            throw new JSONException("Release is outside the selected channel");
        String tag = release.getString("tag_name");
        var match = TAG.matcher(tag);
        if (!match.matches() || tag.length() > 100) throw new JSONException("Invalid version tag");
        long code = Long.parseLong(match.group(1));
        if (code > Integer.MAX_VALUE) throw new JSONException("Invalid version code");
        String name = match.group(2);
        String page = release.getString("html_url");
        String path = URI.create(includePrereleases ? SOURCE_RELEASES_URL : RELEASES_URL).getRawPath() + "/";
        if (!githubPath(page, path + "tag/" + tag)) throw new JSONException("Invalid release link");
        String apkName = "coloros-enhance-" + name + ".apk";
        JSONArray assets = release.getJSONArray("assets");
        String apk = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (!apkName.equals(asset.optString("name")) || !"uploaded".equals(asset.optString("state"))
                    || asset.optLong("size", 0) <= 0) continue;
            String url = asset.getString("browser_download_url");
            if (githubPath(url, path + "download/" + tag + "/" + apkName)) apk = url;
        }
        if (apk == null) throw new JSONException("Release APK unavailable");
        String notes = release.isNull("body") ? "" : release.optString("body", "");
        String html = release.isNull("body_html") ? "" : release.optString("body_html", "");
        if (notes.length() > 20_000) notes = notes.substring(0, 20_000) + "\n…完整说明请查看发布页。";
        if (html.length() > 80_000) html = ""; // Keep complete tags or display the limited plain-text fallback.
        return new ReleaseInfo(code, name, notes, html, page, apk, prerelease);
    }

    String notesLink(String target) {
        try {
            URI link = URI.create(pageUrl).resolve(target);
            return "https".equalsIgnoreCase(link.getScheme()) && link.getHost() != null && link.getRawUserInfo() == null
                    ? link.toString() : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }

    private static boolean githubPath(String url, String expectedPath) {
        try {
            URI uri = URI.create(url);
            return "https".equals(uri.getScheme()) && "github.com".equals(uri.getHost())
                    && uri.getRawUserInfo() == null && uri.getPort() == -1 && uri.getRawQuery() == null
                    && uri.getRawFragment() == null && expectedPath.equals(uri.getRawPath());
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
