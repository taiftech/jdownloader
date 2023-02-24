//  jDownloader - Downloadmanager
//  Copyright (C) 2013  JD-Team support@jdownloader.org
//
//  This program is free software: you can redistribute it and/or modify
//  it under the terms of the GNU General Public License as published by
//  the Free Software Foundation, either version 3 of the License, or
//  (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//  GNU General Public License for more details.
//
//  You should have received a copy of the GNU General Public License
//  along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import org.appwork.storage.JSonStorage;
import org.appwork.storage.TypeRef;
import org.appwork.storage.config.JsonConfig;
import org.appwork.uio.ConfirmDialogInterface;
import org.appwork.uio.UIOManager;
import org.appwork.utils.Application;
import org.appwork.utils.DebugMode;
import org.appwork.utils.StringUtils;
import org.appwork.utils.encoding.URLEncode;
import org.appwork.utils.formatter.TimeFormatter;
import org.appwork.utils.os.CrossSystem;
import org.appwork.utils.parser.UrlQuery;
import org.appwork.utils.swing.dialog.ConfirmDialog;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperHostPluginRecaptchaV2;
import org.jdownloader.plugins.components.config.GoogleConfig;
import org.jdownloader.plugins.components.config.GoogleConfig.APIDownloadMode;
import org.jdownloader.plugins.components.config.GoogleConfig.PreferredVideoQuality;
import org.jdownloader.plugins.components.google.GoogleHelper;
import org.jdownloader.plugins.components.youtube.YoutubeHelper;
import org.jdownloader.plugins.components.youtube.YoutubeStreamData;
import org.jdownloader.plugins.config.PluginConfigInterface;
import org.jdownloader.plugins.config.PluginJsonConfig;
import org.jdownloader.plugins.controller.LazyPlugin.FEATURE;
import org.jdownloader.scripting.JavaScriptEngineFactory;
import org.jdownloader.settings.GeneralSettings;

import jd.PluginWrapper;
import jd.controlling.AccountController;
import jd.http.Browser;
import jd.http.Cookie;
import jd.http.Cookies;
import jd.nutils.encoding.Encoding;
import jd.nutils.encoding.HTMLEntities;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.parser.html.HTMLParser;
import jd.parser.html.HTMLSearch;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.AccountInvalidException;
import jd.plugins.AccountRequiredException;
import jd.plugins.AccountUnavailableException;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.PluginJSonUtils;
import jd.plugins.decrypter.GoogleDriveCrawler;
import jd.plugins.download.HashInfo;
import jd.plugins.download.raf.HTTPDownloader;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = {}, urls = {})
public class GoogleDrive extends PluginForHost {
    public GoogleDrive(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://accounts.google.com/signup");
    }

    @Override
    public String getAGBLink() {
        return "https://support.google.com/drive/answer/2450387?hl=en-GB";
    }

    @Override
    public String[] siteSupportedNames() {
        return new String[] { "drive.google.com", "docs.google.com", "googledrive" };
    }

    public static List<String[]> getPluginDomains() {
        final List<String[]> ret = new ArrayList<String[]>();
        // each entry in List<String[]> will result in one PluginForHost, Plugin.getHost() will return String[0]->main domain
        ret.add(new String[] { "drive.google.com", "docs.google.com" });
        return ret;
    }

    public static String[] getAnnotationNames() {
        return buildAnnotationNames(getPluginDomains());
    }

    @Override
    public FEATURE[] getFeatures() {
        return new FEATURE[] { FEATURE.FAVICON };
    }

    @Override
    public Object getFavIcon(String host) throws IOException {
        if ("drive.google.com".equals(host) || "docs.google.com".equals(host)) {
            /**
             * Required because websites redirect to login page for anonymous users which would not let auto handling fetch correct favicon.
             */
            if ("docs.google.com".equals(host)) {
                return "https://ssl.gstatic.com/docs/documents/images/kix-favicon7.ico";
            } else {
                return "https://drive.google.com/favicon.ico";
            }
        } else {
            return null;
        }
    }

    public static String[] getAnnotationUrls() {
        final List<String> ret = new ArrayList<String>();
        for (final String[] domains : getPluginDomains()) {
            String regex = "https?://" + buildHostsPatternPart(domains) + "/(?:";
            regex += "(?:leaf|open)\\?([^<>\"/]+)?id=[A-Za-z0-9\\-_]+.*";
            regex += "|(?:u/\\d+/)?uc(?:\\?|.*?&)id=[A-Za-z0-9\\-_]+.*";
            regex += "|(?:a/[a-zA-z0-9\\.]+/)?(?:file|document)/d/[A-Za-z0-9\\-_]+.*";
            regex += ")";
            /*
             * Special case: Embedded video URLs with subdomain that is not given in our list of domains because it only supports this
             * pattern!
             */
            regex += "|https?://video\\.google\\.com/get_player\\?docid=[A-Za-z0-9\\-_]+";
            ret.add(regex);
        }
        return ret.toArray(new String[0]);
    }

    @Override
    public boolean isSpeedLimited(final DownloadLink link, final Account account) {
        return false;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        final String fileID = getFID(link);
        if (fileID != null) {
            return getHost().concat("://".concat(fileID));
        } else {
            return super.getLinkID(link);
        }
    }

    private static Object      LOCK                          = new Object();
    private String             websiteWebapiKey              = null;
    public static final String API_BASE                      = "https://www.googleapis.com/drive/v3";
    private final String       PATTERN_GDOC                  = "(?i)https?://.*/document/d/([a-zA-Z0-9\\-_]+).*";
    private final String       PATTERN_FILE                  = "(?i)https?://.*/file/d/([a-zA-Z0-9\\-_]+).*";
    private final String       PATTERN_FILE_OLD              = "(?i)https?://[^/]+/(?:leaf|open)\\?([^<>\"/]+)?id=([A-Za-z0-9\\-_]+).*";
    private final String       PATTERN_FILE_DOWNLOAD_PAGE    = "(?i)https?://[^/]+/(?:u/\\d+/)?uc(?:\\?|.*?&)id=([A-Za-z0-9\\-_]+).*";
    private final String       PATTERN_VIDEO_STREAM          = "(?i)https?://video\\.google\\.com/get_player\\?docid=([A-Za-z0-9\\-_]+)";
    private final boolean      canHandleGoogleSpecialCaptcha = false;

    private String getFID(final DownloadLink link) {
        if (link == null) {
            return null;
        } else if (link.getPluginPatternMatcher() == null) {
            return null;
        } else {
            if (link.getPluginPatternMatcher().matches(PATTERN_GDOC)) {
                return new Regex(link.getPluginPatternMatcher(), PATTERN_GDOC).getMatch(0);
            } else if (link.getPluginPatternMatcher().matches(PATTERN_FILE)) {
                return new Regex(link.getPluginPatternMatcher(), PATTERN_FILE).getMatch(0);
            } else if (link.getPluginPatternMatcher().matches(PATTERN_VIDEO_STREAM)) {
                return new Regex(link.getPluginPatternMatcher(), PATTERN_VIDEO_STREAM).getMatch(0);
            } else if (link.getPluginPatternMatcher().matches(PATTERN_FILE_OLD)) {
                return new Regex(link.getPluginPatternMatcher(), PATTERN_FILE_OLD).getMatch(0);
            } else if (link.getPluginPatternMatcher().matches(PATTERN_FILE_DOWNLOAD_PAGE)) {
                return new Regex(link.getPluginPatternMatcher(), PATTERN_FILE_DOWNLOAD_PAGE).getMatch(0);
            } else {
                logger.warning("Developer mistake!! URL with unknown pattern:" + link.getPluginPatternMatcher());
                return null;
            }
        }
    }

    /**
     * Google has added this parameter to some long time shared URLs as of October 2021 to make those safer. </br>
     * https://support.google.com/a/answer/10685032?p=update_drives&visit_id=637698313083783702-233025620&rd=1
     */
    private String getFileResourceKey(final DownloadLink link) {
        try {
            return UrlQuery.parse(link.getPluginPatternMatcher()).get("resourcekey");
        } catch (final Throwable ignore) {
            return null;
        }
    }

    public static enum JsonSchemeType {
        API,
        WEBSITE;
    }

    /** DownloadLink properties */
    /**
     * Contains the quality modifier of the last chosen quality. This property gets reset on reset DownloadLink to ensure that a user cannot
     * change the quality and then resume the started download with another URL.
     */
    private final String        PROPERTY_USED_QUALITY                          = "USED_QUALITY";
    private static final String PROPERTY_GOOGLE_DOCUMENT                       = "IS_GOOGLE_DOCUMENT";
    private static final String PROPERTY_FORCED_FINAL_DOWNLOADURL              = "FORCED_FINAL_DOWNLOADURL";
    private static final String PROPERTY_CAN_DOWNLOAD                          = "CAN_DOWNLOAD";
    private final String        PROPERTY_CAN_STREAM                            = "CAN_STREAM";
    private final String        PROPERTY_IS_INFECTED                           = "is_infected";
    private final String        PROPERTY_LAST_IS_PRIVATE_FILE_TIMESTAMP        = "LAST_IS_PRIVATE_FILE_TIMESTAMP";
    private final String        PROPERTY_IS_QUOTA_REACHED_ANONYMOUS            = "IS_QUOTA_REACHED_ANONYMOUS";
    private final String        PROPERTY_IS_QUOTA_REACHED_ACCOUNT              = "IS_QUOTA_REACHED_ACCOUNT";
    private final String        PROPERTY_IS_STREAM_QUOTA_REACHED_ANONYMOUS     = "IS_STREAM_QUOTA_REACHED_ANONYMOUS";
    private final String        PROPERTY_IS_STREAM_QUOTA_REACHED_ACCOUNT       = "IS_STREAM_QUOTA_REACHED_ACCOUNT";
    private final String        PROPERTY_DIRECTURL                             = "directurl";
    private final String        PROPERTY_TMP_ALLOW_OBTAIN_MORE_INFORMATION     = "tmp_allow_obtain_more_information";
    private final static String PROPERTY_CACHED_FILENAME                       = "cached_filename";
    private final static String PROPERTY_CACHED_LAST_DISPOSITION_STATUS        = "cached_last_disposition_status";
    private final String        DISPOSITION_STATUS_QUOTA_EXCEEDED              = "QUOTA_EXCEEDED";
    /**
     * 2022-02-20: We store this property but we're not using it at this moment. It is required to access some folders though so it's good
     * to have it set on each DownloadLink if it exists.
     */
    public static final String  PROPERTY_TEAM_DRIVE_ID                         = "TEAM_DRIVE_ID";
    /* Packagizer property */
    public static final String  PROPERTY_ROOT_DIR                              = "root_dir";
    /* Account properties */
    private final String        PROPERTY_ACCOUNT_ACCESS_TOKEN                  = "ACCESS_TOKEN";
    private final String        PROPERTY_ACCOUNT_REFRESH_TOKEN                 = "REFRESH_TOKEN";
    private final String        PROPERTY_ACCOUNT_ACCESS_TOKEN_EXPIRE_TIMESTAMP = "ACCESS_TOKEN_EXPIRE_TIMESTAMP";

    public Browser prepBrowser(final Browser pbr) {
        pbr.getHeaders().put("Accept-Language", "en-gb, en;q=0.9");
        pbr.setCustomCharset("utf-8");
        pbr.setFollowRedirects(true);
        pbr.setAllowedResponseCodes(new int[] { 429 });
        return pbr;
    }

    public static Browser prepBrowserAPI(final Browser br) {
        br.setAllowedResponseCodes(new int[] { 400 });
        return br;
    }

    private boolean isGoogleDocument(final DownloadLink link) {
        if (link.hasProperty(PROPERTY_GOOGLE_DOCUMENT)) {
            /* Return stored property (= do not care about the URL). */
            return link.getBooleanProperty(PROPERTY_GOOGLE_DOCUMENT, false);
        } else if (link.getPluginPatternMatcher().matches(PATTERN_GDOC)) {
            /* URL looks like GDoc */
            return true;
        } else {
            /* Assume it's not a google document! */
            return false;
        }
    }

    /** Returns true if this link has the worst "Quota reached" status: It is currently not even downloadable via account. */
    private boolean isDownloadQuotaReachedAccount(final DownloadLink link) {
        if (System.currentTimeMillis() - link.getLongProperty(PROPERTY_IS_QUOTA_REACHED_ACCOUNT, 0) < 10 * 60 * 1000l) {
            return true;
        } else {
            return false;
        }
    }

    /** Returns true if this link has the worst "Streaming Quota reached" status: It is currently not even downloadable via account. */
    private boolean isStreamQuotaReachedAccount(final DownloadLink link) {
        if (System.currentTimeMillis() - link.getLongProperty(PROPERTY_IS_STREAM_QUOTA_REACHED_ACCOUNT, 0) < 10 * 60 * 1000l) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Returns true if this link has the most common "Quota reached" status: It is currently only downloadable via account (or not
     * downloadable at all).
     */
    private boolean isDownloadQuotaReachedAnonymous(final DownloadLink link) {
        if (System.currentTimeMillis() - link.getLongProperty(PROPERTY_IS_QUOTA_REACHED_ANONYMOUS, 0) < 10 * 60 * 1000l) {
            return true;
        } else if (isDownloadQuotaReachedAccount(link)) {
            /* If a file is quota limited in account mode, it is quota limited in anonymous download mode too. */
            return true;
        } else {
            return false;
        }
    }

    /** Returns state of flag set during API availablecheck. */
    private boolean canDownload(final DownloadLink link) {
        if (isInfected(link)) {
            return false;
        } else {
            return link.getBooleanProperty(PROPERTY_CAN_DOWNLOAD, true);
        }
    }

    private boolean isInfected(final DownloadLink link) {
        return link.getBooleanProperty(PROPERTY_IS_INFECTED, false);
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws Exception {
        return requestFileInformation(link, AccountController.getInstance().getValidAccount(this.getHost()), false);
    }

    private AvailableStatus requestFileInformation(final DownloadLink link, final Account account, final boolean isDownload) throws Exception {
        if (this.useAPIForLinkcheck()) {
            return this.requestFileInformationAPI(link, isDownload);
        } else {
            return this.requestFileInformationWebsite(link, account, isDownload);
        }
    }

    private AvailableStatus requestFileInformationAPI(final DownloadLink link, final boolean isDownload) throws Exception {
        final String fid = this.getFID(link);
        if (fid == null) {
            /* Developer mistake */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (!link.isNameSet()) {
            /* Set fallback name */
            link.setName(fid);
        }
        final String fileResourceKey = getFileResourceKey(link);
        prepBrowserAPI(this.br);
        if (fileResourceKey != null) {
            GoogleDriveCrawler.setResourceKeyHeaderAPI(br, fid, fileResourceKey);
        }
        final UrlQuery queryFile = new UrlQuery();
        queryFile.appendEncoded("fileId", fid);
        queryFile.add("supportsAllDrives", "true");
        queryFile.appendEncoded("fields", getSingleFilesFieldsAPI());
        queryFile.appendEncoded("key", getAPIKey());
        br.getPage(GoogleDrive.API_BASE + "/files/" + fid + "?" + queryFile.toString());
        this.handleErrorsAPI(this.br, link, null);
        final Map<String, Object> entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        parseFileInfoAPIAndWebsiteWebAPI(this, JsonSchemeType.API, link, true, true, true, entries);
        return AvailableStatus.TRUE;
    }

    /** Contains all fields we need for file/folder API requests. */
    public static final String getSingleFilesFieldsAPI() {
        return "kind,mimeType,id,name,size,description,md5Checksum,exportLinks,capabilities(canDownload),resourceKey,modifiedTime";
    }

    public static final String getSingleFilesFieldsWebsite() {
        return "kind,mimeType,id,title,fileSize,description,md5Checksum,exportLinks,capabilities(canDownload),resourceKey,modifiedDate";
    }

    private boolean useAPIForLinkcheck() {
        return canUseAPI();
    }

    /** Multiple factors decide whether we want to use the API for downloading or use the website. */
    private boolean useAPIForDownloading(final DownloadLink link, final Account account) {
        if (!canUseAPI()) {
            /* No API download possible */
            return false;
        }
        /*
         * Download via API is generally allowed. Now check for cases where we'd like to prefer website download.
         */
        if (account != null && PluginJsonConfig.get(GoogleConfig.class).getAPIDownloadMode() == APIDownloadMode.WEBSITE_IF_ACCOUNT_AVAILABLE) {
            /* Always prefer download via website with account to avoid "quota reached" errors. */
            return false;
        } else if (account != null && !this.isDownloadQuotaReachedAccount(link) && PluginJsonConfig.get(GoogleConfig.class).getAPIDownloadMode() == APIDownloadMode.WEBSITE_IF_ACCOUNT_AVAILABLE_AND_FILE_IS_QUOTA_LIMITED) {
            /*
             * Prefer download via website (avoid API) with account to avoid "quota reached" errors for specific links which we know are
             * quota limited.
             */
            return false;
        } else {
            /* Prefer API download for all other cases. */
            return true;
        }
    }

    public static void parseFileInfoAPIAndWebsiteWebAPI(final Plugin plugin, final JsonSchemeType schemetype, final DownloadLink link, final boolean setMd5hash, final boolean setFinalFilename, final boolean setVerifiedFilesize, final Map<String, Object> entries) {
        final boolean isWebsite = schemetype == JsonSchemeType.WEBSITE;
        final String mimeType = (String) entries.get("mimeType");
        final String filename;
        if (isWebsite) {
            filename = entries.get("title").toString();
        } else {
            filename = entries.get("name").toString();
        }
        final String md5Checksum = (String) entries.get("md5Checksum");
        /* API returns size as String so we need to parse it. */
        final long filesize;
        if (isWebsite) {
            filesize = JavaScriptEngineFactory.toLong(entries.get("fileSize"), -1);
        } else {
            filesize = JavaScriptEngineFactory.toLong(entries.get("size"), -1);
        }
        final String description = (String) entries.get("description");
        /* E.g. application/vnd.google-apps.document | application/vnd.google-apps.spreadsheet */
        final String googleDriveDocumentType = new Regex(mimeType, "application/vnd\\.google-apps\\.(.+)").getMatch(0);
        if (googleDriveDocumentType != null) {
            /* Google Document */
            final Object exportFormatDownloadurlsO = entries.get("exportLinks");
            final Map<String, Object> exportFormatDownloadurls = exportFormatDownloadurlsO != null ? (Map<String, Object>) exportFormatDownloadurlsO : null;
            parseGoogleDocumentPropertiesAPIAndSetFilename(plugin, link, filename, googleDriveDocumentType, exportFormatDownloadurls);
        } else if (setFinalFilename) {
            link.setFinalFileName(filename);
        } else {
            link.setName(filename);
        }
        link.setProperty(PROPERTY_CACHED_FILENAME, filename);
        if (filesize > -1) {
            if (setVerifiedFilesize) {
                link.setVerifiedFileSize(filesize);
            } else {
                link.setDownloadSize(filesize);
            }
        }
        if (!StringUtils.isEmpty(md5Checksum) && setMd5hash) {
            link.setMD5Hash(md5Checksum);
        }
        if (!StringUtils.isEmpty(description) && StringUtils.isEmpty(link.getComment())) {
            link.setComment(description);
        }
        final String modifiedDate;
        if (isWebsite) {
            modifiedDate = (String) entries.get("modifiedDate");
        } else {
            modifiedDate = (String) entries.get("modifiedTime");
        }
        if (modifiedDate != null) {
            final long lastModifiedDate = TimeFormatter.getMilliSeconds(modifiedDate, "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH);
            link.setLastModifiedTimestamp(lastModifiedDate);
        }
        link.setProperty(GoogleDrive.PROPERTY_TEAM_DRIVE_ID, entries.get("teamDriveId"));
        link.setProperty(PROPERTY_CAN_DOWNLOAD, JavaScriptEngineFactory.walkJson(entries, "capabilities/canDownload"));
        link.setAvailable(true);
    }

    /** Sets filename- and required parameters for GDocs files. */
    public static void parseGoogleDocumentPropertiesAPIAndSetFilename(final Plugin plg, final DownloadLink link, final String filename, final String googleDriveDocumentType, final Map<String, Object> exportFormatDownloadurls) {
        /**
         * Google Drive documents: Either created directly on Google Drive or user added a "real" document-file to GDrive and converted it
         * into a GDoc later. </br>
         * In this case, the "filename" is more like a title no matter whether or not it contains a file-extension.</br>
         * If it contains a file-extension we will try to find download the output format accordingly. </br>
         * For GDocs usually there is no filesize given because there is no "original" file anymore. The filesize depends on the format we
         * chose to download the file in.
         */
        link.setProperty(PROPERTY_GOOGLE_DOCUMENT, true);
        /* Assume that a filename/title has to be given. */
        if (StringUtils.isEmpty(filename)) {
            /* This should never happen */
            return;
        }
        String docDownloadURL = null;
        String fileExtension = Plugin.getFileNameExtensionFromString(filename);
        if (fileExtension != null && exportFormatDownloadurls != null) {
            fileExtension = fileExtension.toLowerCase(Locale.ENGLISH).replace(".", "");
            final Iterator<Entry<String, Object>> iterator = exportFormatDownloadurls.entrySet().iterator();
            while (iterator.hasNext()) {
                final String docDownloadURLCandidate = (String) iterator.next().getValue();
                if (docDownloadURLCandidate.toLowerCase(Locale.ENGLISH).contains("exportformat=" + fileExtension)) {
                    docDownloadURL = docDownloadURLCandidate;
                    break;
                }
            }
        }
        if (!StringUtils.isEmpty(docDownloadURL)) {
            /* We found an export format suiting our filename-extension --> Prefer that */
            link.setProperty(PROPERTY_FORCED_FINAL_DOWNLOADURL, docDownloadURL);
            link.setFinalFileName(filename);
        } else if (googleDriveDocumentType.equalsIgnoreCase("document")) {
            /* Download in OpenDocument format. */
            link.setFinalFileName(plg.applyFilenameExtension(filename, ".odt"));
            if (exportFormatDownloadurls != null && exportFormatDownloadurls.containsKey("application/vnd.oasis.opendocument.text")) {
                link.setProperty(PROPERTY_FORCED_FINAL_DOWNLOADURL, exportFormatDownloadurls.get("application/vnd.oasis.opendocument.text"));
            }
        } else if (googleDriveDocumentType.equalsIgnoreCase("spreadsheet")) {
            /* Download in OpenDocument format. */
            link.setFinalFileName(plg.applyFilenameExtension(filename, ".ods"));
            if (exportFormatDownloadurls != null && exportFormatDownloadurls.containsKey("application/x-vnd.oasis.opendocument.spreadsheet")) {
                link.setProperty(PROPERTY_FORCED_FINAL_DOWNLOADURL, exportFormatDownloadurls.get("application/x-vnd.oasis.opendocument.spreadsheet"));
            }
        } else {
            /* Unknown document type: Fallback - try to download document as .zip archive. */
            if (exportFormatDownloadurls != null && exportFormatDownloadurls.containsKey("application/zip")) {
                link.setProperty(PROPERTY_FORCED_FINAL_DOWNLOADURL, exportFormatDownloadurls.get("application/zip"));
            }
            link.setFinalFileName(filename + ".zip");
        }
        link.setProperty(PROPERTY_CACHED_FILENAME, link.getFinalFileName());
    }

    private AvailableStatus requestFileInformationWebsite(final DownloadLink link, final Account account, final boolean isDownload) throws Exception {
        final String fid = getFID(link);
        if (fid == null) {
            /** This should never happen */
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (!link.isNameSet()) {
            /* Set fallback name */
            link.setName(fid);
        }
        /* Make sure the value in this property is always fresh. */
        link.removeProperty(PROPERTY_DIRECTURL);
        /* Login whenever possible */
        if (account != null) {
            this.loginDuringLinkcheckOrDownload(br, account);
        }
        prepBrowser(this.br);
        try {
            boolean performDeeperOfflineCheck = false;
            try {
                final AvailableStatus status = this.handleLinkcheckQuick(br, link, account);
                final boolean itemIsEligableForObtainingMoreInformation = link.hasProperty(PROPERTY_TMP_ALLOW_OBTAIN_MORE_INFORMATION);
                if (status == AvailableStatus.TRUE) {
                    final boolean deeperCheckHasAlreadyBeenPerformed = link.getFinalFileName() != null || this.isGoogleDocument(link) || link.getView().getBytesTotal() > 0;
                    if (!itemIsEligableForObtainingMoreInformation || deeperCheckHasAlreadyBeenPerformed) {
                        return status;
                    } else {
                        logger.info("File is online but we'll be looking for more information about this one");
                    }
                } else {
                    logger.info("Do not trust quick linkcheck as it returned status != AVAILABLE");
                }
            } catch (final PluginException exc) {
                if (exc.getLinkStatus() == LinkStatus.ERROR_FILE_NOT_FOUND) {
                    if (PluginJsonConfig.get(GoogleConfig.class).isDebugWebsiteTrustQuickLinkcheckOfflineStatus()) {
                        throw exc;
                    } else {
                        logger.info("Looks like that file is offline -> Double-checking as it could also be a private file!");
                        performDeeperOfflineCheck = true;
                    }
                } else {
                    throw exc;
                }
            }
            logger.info("Checking availablestatus via file overview");
            this.handleLinkcheckFileOverview(br, link, account, isDownload, performDeeperOfflineCheck);
        } catch (final AccountRequiredException ae) {
            if (isDownload) {
                throw ae;
            } else {
                return AvailableStatus.TRUE;
            }
        }
        return AvailableStatus.TRUE;
    }

    private void debugPrintCookies(final Browser br) {
        /* 2023-01-25: Hunting bad "Insufficient permissions" bug as it must be related to account cookies */
        System.out.println("****************************************");
        final Iterator<Entry<String, Cookies>> iterator = br.getCookies().entrySet().iterator();
        while (iterator.hasNext()) {
            final Entry<String, Cookies> entry = iterator.next();
            System.out.println("Domain: " + entry.getKey());
            for (final Cookie cookie : entry.getValue().getCookies()) {
                System.out.println(cookie.getKey() + ": " + cookie.getValue());
            }
        }
        System.out.println("****************************************");
    }

    private AvailableStatus handleLinkcheckQuick(final Browser br, final DownloadLink link, final Account account) throws PluginException, IOException, InterruptedException {
        logger.info("Attempting quick linkcheck");
        link.removeProperty(PROPERTY_DIRECTURL);
        link.removeProperty(PROPERTY_CAN_DOWNLOAD);
        link.removeProperty(PROPERTY_IS_INFECTED);
        link.removeProperty(PROPERTY_CACHED_LAST_DISPOSITION_STATUS);
        removeQuotaReachedFlags(link, account);
        link.setProperty(PROPERTY_TMP_ALLOW_OBTAIN_MORE_INFORMATION, true);
        br.getHeaders().put("X-Drive-First-Party", "DriveViewer");
        final UrlQuery query = new UrlQuery();
        query.add("id", this.getFID(link));
        /* 2020-12-01: authuser=0 also for logged-in users! */
        query.add("authuser", "0");
        query.add("export", "download");
        br.postPage("https://drive.google.com/uc?" + query.toString(), "");
        if (br.getHttpConnection().getResponseCode() == 403) {
            this.errorAccountRequiredOrPrivateFile(br, link, account);
        } else if (br.getHttpConnection().getResponseCode() == 404) {
            /*
             * 2023-02-23: Looks like this is sometimes returning status 404 for files where an account is required so we can't trust this
             * 100%.
             */
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final String json = br.getRegex(".*(\\{.+\\})$").getMatch(0);
        final Map<String, Object> entries = restoreFromString(json, TypeRef.MAP);
        final String filename = (String) entries.get("fileName");
        final Number filesizeO = (Number) entries.get("sizeBytes");
        if (filesizeO != null) {
            /* Filesize field will be 0 for google docs and given downloadUrl will be broken. */
            final long filesize = filesizeO.longValue();
            if (filesize == 0) {
                /**
                 * Do not trust this filename as it is most likely missing a file-extension. </br>
                 * Assume that it is a google document while not trusting it and set default file extension for google doc downloads.
                 */
                /* Do NOT set google document flag as we can't be sure that this is a google doc. */
                // link.setProperty(PATTERN_GDOC, true);
                if (!StringUtils.isEmpty(filename)) {
                    link.setName(this.correctOrApplyFileNameExtension(filename, ".zip"));
                }
                return AvailableStatus.TRUE;
            } else {
                link.setVerifiedFileSize(filesize);
            }
        }
        if (!StringUtils.isEmpty(filename)) {
            link.setFinalFileName(filename);
            link.setProperty(PROPERTY_CACHED_FILENAME, filename);
        } else {
            /* This handling primarily exists because stream download handling can alter pre-set final filenames so */
            final String cachedFilename = link.getStringProperty(PROPERTY_CACHED_FILENAME);
            if (cachedFilename != null) {
                link.setFinalFileName(null);
                link.setName(cachedFilename);
            }
        }
        final String directurl = (String) entries.get("downloadUrl");
        final String scanResult = (String) entries.get("scanResult");
        if (scanResult != null && scanResult.equalsIgnoreCase("ERROR")) {
            /* Assume that this has happened: {"disposition":"QUOTA_EXCEEDED","scanResult":"ERROR"} */
            link.setProperty(PROPERTY_TMP_ALLOW_OBTAIN_MORE_INFORMATION, true);
            final String disposition = entries.get("disposition").toString();
            link.setProperty(PROPERTY_CACHED_LAST_DISPOSITION_STATUS, disposition);
            if (disposition.equalsIgnoreCase("FILE_INFECTED_NOT_OWNER")) {
                link.setProperty(PROPERTY_IS_INFECTED, true);
                return AvailableStatus.TRUE;
            } else if (disposition.equalsIgnoreCase("DOWNLOAD_RESTRICTED")) {
                /* Official download impossible -> Stream download may still be possible */
                link.setProperty(PROPERTY_CAN_DOWNLOAD, false);
                return AvailableStatus.TRUE;
            } else if (disposition.equalsIgnoreCase(this.DISPOSITION_STATUS_QUOTA_EXCEEDED)) {
                link.setProperty(PROPERTY_IS_QUOTA_REACHED_ANONYMOUS, System.currentTimeMillis());
                if (account != null) {
                    link.setProperty(PROPERTY_IS_QUOTA_REACHED_ACCOUNT, System.currentTimeMillis());
                }
                return AvailableStatus.TRUE;
            } else {
                /* Unknown error state */
                throw new PluginException(LinkStatus.ERROR_FATAL, disposition);
            }
        } else {
            /* Typically with scanResult == "CLEAN_FILE" or "SCAN_CLEAN" */
            link.setProperty(PROPERTY_DIRECTURL, directurl);
            link.removeProperty(PROPERTY_TMP_ALLOW_OBTAIN_MORE_INFORMATION);
            logger.info("Experimental linkcheck successful and file should be downloadable");
            return AvailableStatus.TRUE;
        }
    }

    /** Check availablestatus via https://drive.google.com/file/d/<fuid> */
    private AvailableStatus handleLinkcheckFileOverview(final Browser br, final DownloadLink link, final Account account, final boolean isDownload, final boolean specialOfflineCheck) throws PluginException, IOException, InterruptedException {
        final String fid = this.getFID(link);
        if (isDownload && canHandleGoogleSpecialCaptcha) {
            synchronized (LOCK) {
                accessFileViewURLWithPartialErrorhandling(br, link, account);
            }
        } else {
            accessFileViewURLWithPartialErrorhandling(br, link, account);
        }
        final boolean looksLikeFileIsOffline = br.getHttpConnection().getResponseCode() == 403 && !br.containsHTML(Pattern.quote(fid));
        if (specialOfflineCheck && looksLikeFileIsOffline) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        this.handleErrorsWebsite(this.br, link, account);
        /** Only look for/set filename/filesize if it hasn't been done before! */
        String filename = br.getRegex("'id'\\s*:\\s*'" + Pattern.quote(fid) + "'\\s*,\\s*'title'\\s*:\\s*'(.*?)'").getMatch(0);
        if (filename == null) {
            filename = br.getRegex("'title'\\s*:\\s*'([^<>\"\\']+)'").getMatch(0);
            if (filename == null) {
                filename = br.getRegex("(?i)<title>([^<]+) - Google Drive\\s*</title>").getMatch(0);
            }
            if (filename == null) {
                filename = HTMLSearch.searchMetaTag(br, "og:title");
            }
        }
        final boolean isGoogleDocument = br.containsHTML("\"docs-dm\":\\s*\"application/vnd\\.google-apps");
        if (filename != null) {
            filename = PluginJSonUtils.unescape(filename);
            filename = Encoding.unicodeDecode(filename).trim();
            if (isGoogleDocument) {
                filename = this.correctOrApplyFileNameExtension(filename, ".zip");
            }
            link.setName(filename);
            link.setProperty(PROPERTY_CACHED_FILENAME, filename);
        } else {
            logger.warning("Failed to find filename");
        }
        /* Try to find precise filesize */
        String filesizeBytesStr = br.getRegex(Pattern.quote(fid) + "/view\"[^\\]]*\\s*,\\s*\"(\\d+)\"\\s*\\]").getMatch(0);
        if (filesizeBytesStr == null) {
            filesizeBytesStr = br.getRegex("\"sizeInBytes\"\\s*:\\s*(\\d+),").getMatch(0);
        }
        if (isGoogleDocument) {
            link.setProperty(PROPERTY_GOOGLE_DOCUMENT, true);
        }
        if (filesizeBytesStr != null) {
            /* Size of original file but the to be downloaded file could be a re-encoded stream with different file size */
            final long filesize = Long.parseLong(filesizeBytesStr);
            /* For Google Documents, filesize will be 0 here -> Do not set it at all as it is unknown. */
            if (filesize > 0 || !isGoogleDocument) {
                link.setDownloadSize(Long.parseLong(filesizeBytesStr));
            }
        } else {
            /* Usually filesize is not given for google documents. */
            if (!isGoogleDocument) {
                logger.warning("Failed to find filesize");
            }
        }
        return AvailableStatus.TRUE;
    }

    private void accessFileViewURLWithPartialErrorhandling(final Browser br, final DownloadLink link, final Account account) throws PluginException, IOException {
        br.getPage(getFileViewURL(link));
        this.websiteWebapiKey = this.findWebsiteWebAPIKey(br);
        /** More errorhandling / offline check */
        if (br.getHttpConnection().getResponseCode() == 404) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        } else if (br.containsHTML("(?i)<p class=\"error\\-caption\">\\s*Sorry, we are unable to retrieve this document\\.\\s*</p>")) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        if (br.containsHTML("\"docs-dm\":\\s*\"video/")) {
            link.setProperty(PROPERTY_CAN_STREAM, true);
        }
    }

    /** A function that parses filename/size/last-modified date from single files via "Details View" of Google Drive website. */
    private void crawlAdditionalFileInformationFromWebsite(final Browser br, final DownloadLink link, final Account account, final boolean accessFileViewPageIfNotAlreadyDone, final boolean trustAndSetFileInfo) throws PluginException, IOException {
        final Browser br2 = br.cloneBrowser();
        if (accessFileViewPageIfNotAlreadyDone && !br2.getURL().matches(PATTERN_FILE) && this.websiteWebapiKey == null) {
            accessFileViewURLWithPartialErrorhandling(br2, link, account);
        }
        if (this.websiteWebapiKey == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        if (account != null) {
            // TODO: https://svn.jdownloader.org/issues/88600
            logger.info("!Dev! This doesn't work in account mode yet!");
            return;
        }
        br2.getHeaders().put("X-Referer", "https://drive.google.com");
        br2.getHeaders().put("X-Origin", "https://drive.google.com");
        br2.getHeaders().put("X-Requested-With", "XMLHttpRequest");
        final UrlQuery query = new UrlQuery();
        query.add("fields", URLEncode.encodeURIComponent(getSingleFilesFieldsWebsite()));
        query.add("supportsTeamDrives", "true");
        // query.add("includeBadgedLabels", "true");
        query.add("enforceSingleParent", "true");
        query.add("key", URLEncode.encodeURIComponent(this.websiteWebapiKey));
        // br2.setAllowedResponseCodes(400);
        br2.getPage("https://content.googleapis.com/drive/v2beta/files/" + this.getFID(link) + "?" + query.toString());
        /* For logged in users: */
        // TODO: This will end up in error response 400 "message": "Authentication token must be issued to a non-anonymous app."
        // br2.getHeaders().put("Authorization", "SAPISIDHASH TODO");
        // br2.getHeaders().put("Referer", "https://clients6.google.com/static/proxy.html");
        // br2.getHeaders().put("x-client-data", "TODO");
        // br2.getHeaders().put("x-clientdetails",
        // "appVersion=5.0%20(Windows%20NT%2010.0%3B%20Win64%3B%20x64)%20AppleWebKit%2F537.36%20(KHTML%2C%20like%20Gecko)%20Chrome%2F108.0.0.0%20Safari%2F537.36&platform=Win32&userAgent=Mozilla%2F5.0%20(Windows%20NT%2010.0%3B%20Win64%3B%20x64)%20AppleWebKit%2F537.36%20(KHTML%2C%20like%20Gecko)%20Chrome%2F108.0.0.0%20Safari%2F537.36");
        // br2.getHeaders().put("x-goog-authuser", "0");
        // br2.getHeaders().put("x-goog-encode-response-if-executable", "base64");
        // br2.getHeaders().put("x-javascript-user-agent:", "google-api-javascript-client/1.1.0");
        // br2.getPage("https://clients6.google.com/drive/v2internal/files/" + this.getFID(link) + "?" + query.toString());
        final Map<String, Object> entries = restoreFromString(br2.getRequest().getHtmlCode(), TypeRef.MAP);
        parseFileInfoAPIAndWebsiteWebAPI(this, JsonSchemeType.WEBSITE, link, trustAndSetFileInfo, trustAndSetFileInfo, trustAndSetFileInfo, entries);
    }

    /** Returns directurl for original file download items and google document items. */
    private String getDirecturl(final DownloadLink link, final Account account) throws PluginException {
        if (this.isGoogleDocument(link)) {
            /* Google document download */
            final String forcedDocumentFinalDownloadlink = link.getStringProperty(PROPERTY_FORCED_FINAL_DOWNLOADURL);
            if (forcedDocumentFinalDownloadlink != null) {
                return forcedDocumentFinalDownloadlink;
            } else {
                /* Default google docs download format. */
                return "https://docs.google.com/feeds/download/documents/export/Export?id=" + this.getFID(link) + "&exportFormat=zip";
            }
        } else {
            /* File download */
            final String lastStoredDirecturl = link.getStringProperty(PROPERTY_DIRECTURL);
            if (lastStoredDirecturl != null) {
                return lastStoredDirecturl;
            } else {
                return constructFileDirectDownloadUrl(link, account);
            }
        }
    }

    /** Returns URL which should redirect to file download in website mode. */
    private String constructFileDirectDownloadUrl(final DownloadLink link, final Account account) throws PluginException {
        final String fid = getFID(link);
        if (fid == null) {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        /**
         * E.g. older alternative URL for documents: https://docs.google.com/document/export?format=pdf&id=<fid>&includes_info_params=true
         * </br>
         * Last rev. with this handling: 42866
         */
        String url = "https://drive.google.com";
        /* Minor difference when user is logged in. They don't really check that but let's mimic browser behavior. */
        if (account != null) {
            url += "/u/0/uc";
        } else {
            url += "/uc";
        }
        url += "?id=" + getFID(link) + "&export=download";
        final String fileResourceKey = this.getFileResourceKey(link);
        if (fileResourceKey != null) {
            url += "&resourcekey=" + fileResourceKey;
        }
        return url;
    }

    private String findWebsiteWebAPIKey(final Browser br) {
        String key = br.getRegex("\"([^\"]+)\",null,\"/drive/v2beta\"").getMatch(0);
        if (key == null) {
            key = br.getRegex("\"([^\"]+)\",null,\"/drive/v2beta\"").getMatch(0);
        }
        if (key == null) {
            key = br.getRegex("\"/drive/v2internal\",\"([^\"]+)\"").getMatch(0);
        }
        return key;
    }

    private String getFileViewURL(final DownloadLink link) {
        final String fileResourceKey = this.getFileResourceKey(link);
        String url = "https://drive.google.com/file/d/" + getFID(link) + "/view";
        if (fileResourceKey != null) {
            url += "?resourcekey=" + fileResourceKey;
        }
        return url;
    }

    private String regexConfirmDownloadurl(final Browser br) throws MalformedURLException {
        String ret = br.getRegex("\"([^\"]*?/uc[^\"]+export=download[^<>\"]*?confirm=[^<>\"]+)\"").getMatch(0);
        if (ret == null) {
            /**
             * We're looking for such an URL (parameter positions may vary and 'resourcekey' parameter is not always given): </br>
             * https://drive.google.com/uc?id=<fileID>&export=download&resourcekey=<key>&confirm=t
             */
            final String[] urls = HTMLParser.getHttpLinks(br.getRequest().getHtmlCode(), br.getURL());
            for (final String url : urls) {
                try {
                    final UrlQuery query = UrlQuery.parse(url);
                    if (query.containsKey("export") && query.containsKey("confirm")) {
                        ret = url;
                        break;
                    }
                } catch (final IOException e) {
                    logger.log(e);
                }
            }
        }
        if (ret == null) {
            /* Fallback */
            final Form dlform = br.getFormbyProperty("id", "downloadForm");
            if (dlform != null) {
                ret = dlform.getAction();
            }
        }
        if (ret != null) {
            ret = HTMLEntities.unhtmlentities(ret);
        }
        return ret;
    }

    /**
     * @return: true: Allow stream download attempt </br>
     *          false: Do not allow stream download -> Download original version of file
     */
    private boolean isStreamDownloadPreferredAndAllowed(final DownloadLink link) {
        if (userPrefersStreamDownload() && videoStreamShouldBeAvailable(link)) {
            return true;
        } else {
            return false;
        }
    }

    private boolean userPrefersStreamDownload() {
        if (PluginJsonConfig.get(GoogleConfig.class).getPreferredVideoQuality() == PreferredVideoQuality.ORIGINAL) {
            return false;
        } else {
            return true;
        }
    }

    private boolean userAllowsStreamDownloadAsFallback() {
        return PluginJsonConfig.get(GoogleConfig.class).isAllowStreamDownloadAsFallback();
    }

    private boolean videoStreamShouldBeAvailable(final DownloadLink link) {
        if (link.hasProperty(PROPERTY_CAN_STREAM)) {
            /* We know that file is streamable. */
            return true;
        } else if (this.isGoogleDocument(link)) {
            /* Google documents can theoretically have video-like filenames but they can never be streamed! */
            return false;
        } else {
            /* Assume streamable status by filename-extension. */
            if (isVideoFile(link.getName())) {
                /* Assume that file is streamable. */
                return true;
            } else {
                return false;
            }
        }
    }

    private String getStreamDownloadurl(final DownloadLink link, final Account account) throws PluginException, IOException, InterruptedException {
        final GoogleConfig cfg = PluginJsonConfig.get(GoogleConfig.class);
        final PreferredVideoQuality qual = cfg.getPreferredVideoQuality();
        if (qual == PreferredVideoQuality.ORIGINAL) {
            /*
             * User probably prefers original quality file but stream download handling expects a preferred stream quality -> Force-Prefer
             * BEST stream quality.
             */
            return this.handleStreamQualitySelection(link, account, PreferredVideoQuality.STREAM_BEST);
        } else {
            return this.handleStreamQualitySelection(link, account, cfg.getPreferredVideoQuality());
        }
    }

    /**
     * Returns preferred video stream quality direct downloadurl and best as fallback. </br>
     * Returns null if given preferred quality is set to ORIGINAL.
     */
    private String handleStreamQualitySelection(final DownloadLink link, final Account account, final PreferredVideoQuality qual) throws PluginException, IOException, InterruptedException {
        int preferredQualityHeight = link.getIntegerProperty(PROPERTY_USED_QUALITY, -1);
        final boolean userHasDownloadedStreamBefore;
        if (preferredQualityHeight != -1) {
            /* Prefer quality that was used for last download attempt. */
            userHasDownloadedStreamBefore = true;
            logger.info("User has downloaded stream before, trying to obtain same quality as before: " + preferredQualityHeight + "p");
        } else {
            userHasDownloadedStreamBefore = false;
            preferredQualityHeight = getPreferredQualityHeight(qual);
        }
        /* Some guard clauses: Conditions in which this function should have never been called. */
        if (preferredQualityHeight <= -1) {
            logger.info("Not attempting stream download because: Original file is preferred");
            return null;
        } else if (!videoStreamShouldBeAvailable(link)) {
            logger.info("Not attempting stream download because: File does not seem to be streamable (no video file)");
            return null;
        }
        logger.info("Attempting stream download");
        synchronized (LOCK) {
            if (account != null) {
                /* Uses a slightly different request than when not logged in but answer is the same. */
                /*
                 * E.g. also possible (reduces number of available video qualities):
                 * https://docs.google.com/get_video_info?formats=android&docid=<fuid>
                 */
                br.getPage("https://drive.google.com/u/0/get_video_info?docid=" + this.getFID(link));
            } else {
                br.getPage("https://drive.google.com/get_video_info?docid=" + this.getFID(link));
            }
            this.handleErrorsWebsite(this.br, link, account);
        }
        final UrlQuery query = UrlQuery.parse(br.toString());
        /* Attempt final fallback/edge-case: Check for download of "un-downloadable" streams. */
        final String errorcodeStr = query.get("errorcode");
        final String errorReason = query.get("reason");
        if (errorcodeStr != null) {
            final int errorCode = Integer.parseInt(errorcodeStr);
            if (errorCode == 100) {
                /* This should never happen but if it does, we know for sure that the file is offline! */
                /* 2020-11-29: E.g. &errorcode=100&reason=Dieses+Video+ist+nicht+vorhanden.& */
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (errorCode == 150) {
                /**
                 * Same as in file-download mode: File is definitely not streamable at this moment! </br>
                 * The original file could still be downloadable via account.
                 */
                /** Similar handling to { @link #errorDownloadQuotaReachedWebsite } */
                if (account != null) {
                    if (this.isDownloadQuotaReachedAccount(link)) {
                        /* This link has already been tried in all download modes and is not downloadable at all at this moment. */
                        errorQuotaReachedInAllModes(link);
                    } else {
                        /* User has never tried non-stream download with account --> This could still work for him. */
                        link.setProperty(PROPERTY_IS_STREAM_QUOTA_REACHED_ACCOUNT, System.currentTimeMillis());
                        throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Stream quota limit reached: Try later or disable stream download in plugin settings and try again", getQuotaReachedWaittime());
                    }
                } else {
                    if (this.isDownloadQuotaReachedAccount(link)) {
                        /* This link has already been tried in all download modes and is not downloadable at all at this moment. */
                        errorQuotaReachedInAllModes(link);
                    } else {
                        link.setProperty(PROPERTY_IS_STREAM_QUOTA_REACHED_ANONYMOUS, true);
                        throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Stream quota limit reached: Try later or add Google account and retry", getQuotaReachedWaittime());
                    }
                }
            } else {
                /* Unknown error happened */
                throw new PluginException(LinkStatus.ERROR_FATAL, "Stream download impossible because: " + errorcodeStr + " | " + errorReason);
            }
        }
        /* Update limit properties */
        removeQuotaReachedFlags(link, account);
        /* Usually same as the title we already have but always with .mp4 ending(?) */
        // final String streamFilename = query.get("title");
        // final String fmt_stream_map = query.get("fmt_stream_map");
        final String url_encoded_fmt_stream_map = query.get("url_encoded_fmt_stream_map");
        if (url_encoded_fmt_stream_map == null) {
            logger.info("Stream download impossible for unknown reasons");
            return null;
        }
        final YoutubeHelper dummy = new YoutubeHelper(this.br, this.getLogger());
        final List<YoutubeStreamData> qualities = new ArrayList<YoutubeStreamData>();
        final String[] qualityInfos = Encoding.urlDecode(url_encoded_fmt_stream_map, false).split(",");
        for (final String qualityInfo : qualityInfos) {
            final UrlQuery qualityQuery = UrlQuery.parse(qualityInfo);
            final YoutubeStreamData yts = dummy.convert(qualityQuery, this.br.getURL());
            qualities.add(yts);
        }
        if (qualities.isEmpty()) {
            /* This should never happen */
            throw new PluginException(LinkStatus.ERROR_FATAL, "Invalid state: Expected streaming download but none is available");
        }
        logger.info("Found " + qualities.size() + " stream qualities");
        String bestQualityDownloadlink = null;
        int bestQualityHeight = 0;
        String selectedQualityDownloadlink = null;
        for (final YoutubeStreamData quality : qualities) {
            if (quality.getItag().getVideoResolution().getHeight() > bestQualityHeight || bestQualityDownloadlink == null) {
                bestQualityHeight = quality.getItag().getVideoResolution().getHeight();
                bestQualityDownloadlink = quality.getUrl();
            }
            if (quality.getItag().getVideoResolution().getHeight() == preferredQualityHeight) {
                selectedQualityDownloadlink = quality.getUrl();
                break;
            }
        }
        final int usedQuality;
        if (selectedQualityDownloadlink != null) {
            logger.info("Using user preferred quality: " + preferredQualityHeight + "p");
            usedQuality = preferredQualityHeight;
        } else {
            /* This should never happen! */
            if (preferredQualityHeight == 0) {
                logger.info("Using best stream quality: " + bestQualityHeight + "p (BEST)");
            } else {
                logger.info("Using best stream quality: " + bestQualityHeight + "p (BEST as fallback)");
            }
            selectedQualityDownloadlink = bestQualityDownloadlink;
            usedQuality = bestQualityHeight;
        }
        /** Reset this because hash could possibly have been set before and is only valid for the original file! */
        link.setHashInfo(null);
        /* Reset this as verifiedFilesize will usually be different from stream filesize. */
        link.setVerifiedFileSize(-1);
        if (!userHasDownloadedStreamBefore && link.getView().getBytesLoaded() > 0) {
            /*
             * User could have started download of original file before: Clear download-progress and potentially partially downloaded file.
             */
            logger.info("Resetting progress because user has downloaded parts of original file before but prefers stream download now");
            link.setChunksProgress(null);
            /* Save the quality we've decided to download in case user stops- and resumes download later. */
            link.setProperty(PROPERTY_USED_QUALITY, usedQuality);
        }
        final String filename = link.getName();
        if (filename != null) {
            /* Update file-extension in filename to .mp4 and add quality identifier to filename if chosen by user. */
            String newFilename = correctOrApplyFileNameExtension(filename, ".mp4");
            if (PluginJsonConfig.get(GoogleConfig.class).isAddStreamQualityIdentifierToFilename()) {
                final String newFilenameEnding = "_" + usedQuality + "p.mp4";
                if (!newFilename.toLowerCase(Locale.ENGLISH).endsWith(newFilenameEnding)) {
                    link.setFinalFileName(newFilename.replaceFirst("(?i)\\.mp4$", newFilenameEnding));
                }
            }
            link.setFinalFileName(newFilename);
        }
        return selectedQualityDownloadlink;
    }

    /**
     * Returns result according to file-extensions listed here:
     * https://support.google.com/drive/answer/2423694/?co=GENIE.Platform%3DiOS&hl=de </br>
     * Last updated: 2020-11-29
     */
    private static boolean isVideoFile(final String filename) {
        /*
         * 2020-11-30: .ogg is also supported but audio streams seem to be the original files --> Do not allow streaming download for .ogg
         * files.
         */
        if (filename == null) {
            return false;
        } else if (new Regex(filename, Pattern.compile(".*\\.(webm|3gp|mov|wmv|mp4|mpeg|mkv|avi|flv|mts|m2ts)$", Pattern.CASE_INSENSITIVE)).matches()) {
            return true;
        } else {
            return false;
        }
    }

    private int getPreferredQualityHeight(final PreferredVideoQuality quality) {
        switch (quality) {
        case STREAM_360P:
            return 360;
        case STREAM_480P:
            return 480;
        case STREAM_720P:
            return 720;
        case STREAM_1080P:
            return 1080;
        case STREAM_BEST:
            return 0;
        default:
            /* Original quality (no stream download) */
            return -1;
        }
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        handleDownload(link, null);
    }

    private void handleDownload(final DownloadLink link, final Account account) throws Exception {
        boolean resume = true;
        int maxChunks = 0;
        if (!resume) {
            maxChunks = 1;
        }
        /* Always use API for linkchecking, even if in the end, website is used for downloading! */
        if (useAPIForLinkcheck()) {
            /* Additionally use API for availablecheck if possible. */
            this.requestFileInformationAPI(link, true);
        }
        /* Account is not always used even if it is available. */
        boolean usedAccount = false;
        String directurl = null;
        String streamDownloadlink = null;
        boolean streamDownloadActive = false;
        if (useAPIForDownloading(link, account)) {
            /* API download */
            logger.info("Download in API mode");
            this.checkUndownloadableConditions(link, account);
            if (this.isGoogleDocument(link)) {
                /* Expect stored directurl to be available. */
                directurl = link.getStringProperty(PROPERTY_FORCED_FINAL_DOWNLOADURL);
                if (StringUtils.isEmpty(directurl)) {
                    this.errorGoogleDocumentDownloadImpossible();
                }
            } else {
                /* Check if user prefers stream download which is only possible via website. */
                if (this.videoStreamShouldBeAvailable(link) && (this.userPrefersStreamDownload() || !this.canDownload(link))) {
                    if (this.userPrefersStreamDownload()) {
                        logger.info("Attempting stream download in API mode");
                    } else {
                        logger.info("Attempting stream download FALLBACK in API mode");
                    }
                    if (account != null) {
                        usedAccount = true;
                        this.loginDuringLinkcheckOrDownload(br, account);
                    }
                    streamDownloadlink = this.getStreamDownloadurl(link, account);
                    if (!StringUtils.isEmpty(streamDownloadlink)) {
                        /* Use found stream downloadlink. */
                        directurl = streamDownloadlink;
                        streamDownloadActive = true;
                    } else if (!this.canDownload(link)) {
                        errorCannotDownload(link);
                    }
                }
                if (StringUtils.isEmpty(directurl)) {
                    /* API */
                    final UrlQuery queryFile = new UrlQuery();
                    queryFile.appendEncoded("fileId", this.getFID(link));
                    queryFile.add("supportsAllDrives", "true");
                    // queryFile.appendEncoded("fields", getFieldsAPI());
                    queryFile.appendEncoded("key", getAPIKey());
                    queryFile.appendEncoded("alt", "media");
                    directurl = GoogleDrive.API_BASE + "/files/" + this.getFID(link) + "?" + queryFile.toString();
                }
            }
            if (streamDownloadActive) {
                logger.info("Downloading stream");
            } else {
                logger.info("Downloading original file");
            }
            this.dl = new jd.plugins.BrowserAdapter().openDownload(br, link, directurl, resume, maxChunks);
        } else {
            /* Website download */
            logger.info("Download in website mode");
            /* Check availablestatus again via website as we're downloading via website. */
            requestFileInformationWebsite(link, account, true);
            this.checkUndownloadableConditions(link, account);
            boolean streamDownloadAttempted = false;
            if (this.isStreamDownloadPreferredAndAllowed(link) || (!this.canDownload(link) && this.videoStreamShouldBeAvailable(link))) {
                if (this.isStreamDownloadPreferredAndAllowed(link)) {
                    /* Stream download because user prefers stream download. */
                    logger.info("Attempting stream download in website mode");
                } else {
                    logger.info("Attempting stream download FALLBACK in website mode");
                }
                /**
                 * Sidenote: Files can be blocked for downloading but streaming may still be possible(rare case). </br>
                 * If downloads are blocked because of "too high traffic", streaming can be blocked too!
                 */
                streamDownloadlink = this.getStreamDownloadurl(link, account);
                streamDownloadAttempted = true;
                if (!StringUtils.isEmpty(streamDownloadlink)) {
                    directurl = streamDownloadlink;
                    streamDownloadActive = true;
                } else if (!this.canDownload(link)) {
                    this.errorCannotDownload(link);
                }
            }
            if (directurl == null) {
                /* Attempt to download original file */
                directurl = getDirecturl(link, account);
            }
            if (StringUtils.isEmpty(directurl)) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
            if (streamDownloadActive) {
                logger.info("Downloading stream");
            } else {
                logger.info("Downloading original file");
            }
            this.dl = new jd.plugins.BrowserAdapter().openDownload(br, link, directurl, resume, maxChunks);
            if (dl.getConnection().getResponseCode() == 500 && !this.looksLikeDownloadableContent(dl.getConnection()) && !this.isGoogleDocument(link)) {
                /* 2022-12-08: Workaround for single Google Documents without .zip download added directly without folder-crawler */
                try {
                    br.followConnection(true);
                } catch (final IOException e) {
                    logger.log(e);
                }
                logger.info("First download attempt failed --> Checking via WebAPI to see if instead of a file this is a Google Document");
                crawlAdditionalFileInformationFromWebsite(br, link, account, true, true);
                if (!this.isGoogleDocument(link)) {
                    /* This should never happen */
                    logger.warning("Not confirmed: No Google Document document -> Something went wrong or item is offline");
                    throw new PluginException(LinkStatus.ERROR_FATAL, "Item is not downloadable for unknown reasons");
                }
                logger.info("Confirmed: This is a Google Document");
                /* Working directurl might be available now. */
                directurl = this.getDirecturl(link, account);
                this.dl = new jd.plugins.BrowserAdapter().openDownload(br, link, directurl, resume, maxChunks);
            } else if (!this.looksLikeDownloadableContent(dl.getConnection())) {
                logger.info("File download attempt failed -> Direct download not possible -> One step more might be required");
                try {
                    br.followConnection(true);
                } catch (final IOException e) {
                    logger.log(e);
                }
                this.handleErrorsWebsite(this.br, link, account);
                /**
                 * 2021-02-02: Interesting behavior of offline content: </br>
                 * Returns 403 when accessed via: https://drive.google.com/file/d/<fuid> </br>
                 * Returns 404 when accessed via: https://docs.google.com/uc?id=<fuid>&export=download
                 */
                /* E.g. "This file is too big for Google to virus-scan it - download anyway?" */
                directurl = regexConfirmDownloadurl(br);
                if (directurl != null) {
                    /* We know that the file is online and downloadable. */
                    logger.info("File is too big for Google v_rus scan but should be downloadable");
                } else {
                    final boolean isDownloadQuotaReached = this.isQuotaReachedWebsiteFile(br, link);
                    if (isDownloadQuotaReached && this.videoStreamShouldBeAvailable(link) && this.userAllowsStreamDownloadAsFallback() && !streamDownloadActive && !streamDownloadAttempted) {
                        /* Download quota limit reached -> Try stream download as last resort fallback */
                        logger.info("Attempting forced stream download in an attempt to get around quota limit");
                        try {
                            streamDownloadlink = this.getStreamDownloadurl(link, account);
                        } catch (final PluginException ignore) {
                            logger.log(ignore);
                        }
                        if (StringUtils.isEmpty(streamDownloadlink)) {
                            logger.info("Stream download fallback failed -> There is nothing we can do to avoid this limit");
                            errorDownloadQuotaReachedWebsite(link, account);
                        } else {
                            directurl = streamDownloadlink;
                            streamDownloadActive = true;
                        }
                    } else {
                        /* Dead end */
                        this.downloadFailedLastResortErrorhandling(link, account, streamDownloadActive);
                    }
                }
                logger.info("Final download attempt");
                this.dl = new jd.plugins.BrowserAdapter().openDownload(br, link, directurl, resume, maxChunks);
            }
            if (account != null) {
                /* Website mode will always use account if available. */
                usedAccount = true;
            }
        }
        if (!this.looksLikeDownloadableContent(this.dl.getConnection())) {
            try {
                br.followConnection(true);
            } catch (final IOException e) {
                logger.log(e);
            }
            this.downloadFailedLastResortErrorhandling(link, account, streamDownloadActive);
        }
        if (JsonConfig.create(GeneralSettings.class).isUseOriginalLastModified() && link.getLastModifiedTimestamp() == -1) {
            try {
                crawlAdditionalFileInformationFromWebsite(br, link, account, true, false);
            } catch (final Exception ignore) {
                logger.log(ignore);
                logger.info("Failed to crawl additional file information due to Exception");
            }
        }
        /* Update quota properties */
        if (account != null && usedAccount) {
            this.removeQuotaReachedFlags(link, account);
        } else {
            this.removeQuotaReachedFlags(link, null);
        }
        /** Set final filename here in case previous handling failed to find a good final filename. */
        final String headerFilename = getFileNameFromHeader(this.dl.getConnection());
        if (link.getFinalFileName() == null && !StringUtils.isEmpty(headerFilename)) {
            link.setFinalFileName(headerFilename);
            link.setProperty(PROPERTY_CACHED_FILENAME, headerFilename);
        }
        final HashInfo hashInfo = HTTPDownloader.parseXGoogHash(getLogger(), this.dl.getConnection());
        if (hashInfo != null) {
            link.setHashInfo(hashInfo);
        }
        this.dl.startDownload();
    }

    /** Checks for conditions which make a file un-downloadable and throws exception if any exist. */
    private void checkUndownloadableConditions(final DownloadLink link, final Account account) throws PluginException {
        final boolean userDisabledAllStreamDownloadOptions = !this.userPrefersStreamDownload() && !this.userAllowsStreamDownloadAsFallback();
        if (this.isInfected(link)) {
            this.errorFileInfected(link);
        } else if (!this.canDownload(link) && !this.videoStreamShouldBeAvailable(link) || (!this.canDownload(link) && this.videoStreamShouldBeAvailable(link) && userDisabledAllStreamDownloadOptions)) {
            this.errorCannotDownload(link);
        } else if ((account == null && this.isDownloadQuotaReachedAnonymous(link)) || account != null && this.isDownloadQuotaReachedAccount(link)) {
            this.errorDownloadQuotaReachedWebsite(link, account);
        }
    }

    /** Call this when download was attempted and is not possible at all. */
    private void downloadFailedLastResortErrorhandling(final DownloadLink link, final Account account, final boolean isStreamDownload) throws PluginException, InterruptedException, IOException {
        if (br.getHttpConnection().getContentType().contains("application/json")) {
            /* Looks like API response -> Check errors accordingly */
            this.handleErrorsAPI(this.br, link, account);
        }
        this.handleErrorsWebsite(this.br, link, account);
        if (this.dl.getConnection().getResponseCode() == 416) {
            this.dl.getConnection().disconnect();
            throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error 416", 5 * 60 * 1000l);
        } else {
            if (this.isGoogleDocument(link)) {
                this.errorGoogleDocumentDownloadImpossible();
            } else {
                if (isStreamDownload) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown error: Stream download failed");
                } else {
                    if (!this.canDownload(link)) {
                        errorCannotDownload(link);
                    } else {
                        throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Unknown error: File download failed");
                    }
                }
            }
        }
    }

    private void loginDuringLinkcheckOrDownload(final Browser br, final Account account) throws Exception {
        if (PluginJsonConfig.get(GoogleConfig.class).isDebugForceValidateLoginAlways()) {
            this.login(br, account, true);
        } else {
            this.login(br, account, false);
        }
    }

    private void checkErrorBlockedByGoogle(final Browser br, final DownloadLink link, final Account account) throws PluginException {
        if (br.getHttpConnection().getResponseCode() == 403 && br.containsHTML("(?i)but your computer or network may be sending automated queries")) {
            /* 2022-02-24 */
            if (account != null) {
                throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Blocked by Google", 5 * 60 * 1000l);
            } else {
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, "Blocked by Google", 5 * 60 * 1000l);
            }
        }
    }

    /**
     * Checks for errors that can happen at "any time". Preferably call this inside synchronized block especially if an account is available
     * in an attempt to avoid having to solve multiple captchas!
     */
    private void handleErrorsWebsite(final Browser br, final DownloadLink link, final Account account) throws PluginException, InterruptedException, IOException {
        checkHandleRateLimit(br, link, account);
        /* Check for other errors */
        checkErrorBlockedByGoogle(br, link, account);
        if (br.containsHTML("(?i)>\\s*Sorry, this file is infected with a virus")) {
            link.setProperty(PROPERTY_IS_INFECTED, true);
            this.errorFileInfected(link);
        } else if (isQuotaReachedWebsiteFile(br, link)) {
            errorDownloadQuotaReachedWebsite(link, account);
        } else if (isAccountRequired(br)) {
            errorAccountRequiredOrPrivateFile(br, link, account);
        } else if (br.getHttpConnection().getResponseCode() == 403) {
            /**
             * Most likely quota error or "Missing permissions" error. </br>
             * 2021-05-19: Important: This can also happen if e.g. this is a private file and permissions are missing! It is hard to detect
             * the exact reason for error as errormessages differ depending on the user set Google website language! </br>
             * 2022-11-17: Treat this as "Private file" for now
             */
            final boolean usePrivateFileHandlingHere = true;
            if (usePrivateFileHandlingHere) {
                errorAccountRequiredOrPrivateFile(br, link, account);
            } else {
                if (account != null) {
                    throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "Insufficient permissions: Private file or quota limit reached", 30 * 60 * 1000l);
                } else {
                    errorDownloadQuotaReachedWebsite(link, account);
                }
            }
        }
    }

    private void errorAccountRequiredOrPrivateFile(final Browser br, final DownloadLink link, final Account account) throws IOException, InterruptedException, PluginException {
        errorAccountRequiredOrPrivateFile(br, link, account, true);
    }

    private void errorAccountRequiredOrPrivateFile(final Browser br, final DownloadLink link, final Account account, final boolean verifyLoginStatus) throws IOException, InterruptedException, PluginException {
        synchronized (LOCK) {
            if (link == null) {
                /* Problem happened during account-check -> Account must be invalid */
                throw new AccountInvalidException();
            } else if (account == null) {
                logger.info("Looks like a private file and no account given -> Ask user to add one");
                throw new AccountRequiredException();
            } else {
                /*
                 * Typically Google will redirect us to accounts.google.com for private files but this can also happen when login session is
                 * expired -> Extra check is needed
                 */
                final String directurl = link.getStringProperty(PROPERTY_DIRECTURL);
                if (account != null && directurl != null) {
                    /* 2023-01-25: TODO: Temporary errorhandling for "Insufficient permissions" error happening for public files. */
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Try again later or delete your google account and re-add it");
                } else {
                    logger.info("Checking if we got a private file or a login session failure");
                    final Browser brc = br.cloneBrowser();
                    final GoogleHelper helper = new GoogleHelper(brc, this.getLogger());
                    helper.validateCookiesGoogleDrive(br, account);
                    /* No exception -> Login session is okay -> We can be sure that this is a private file! */
                    link.setProperty(PROPERTY_LAST_IS_PRIVATE_FILE_TIMESTAMP, System.currentTimeMillis());
                    throw new PluginException(LinkStatus.ERROR_FATAL, "Insufficient permissions: Private file");
                }
            }
        }
    }

    private boolean isQuotaReachedWebsiteFile(final Browser br, final DownloadLink link) {
        if (br.containsHTML("(?i)error\\-subcaption\">Too many users have viewed or downloaded this file recently\\. Please try accessing the file again later\\.|<title>Google Drive – (Quota|Cuota|Kuota|La quota|Quote)")) {
            return true;
        } else if (br.containsHTML("class=\"uc\\-error\\-caption\"") && StringUtils.equals(DISPOSITION_STATUS_QUOTA_EXCEEDED, link.getStringProperty(PROPERTY_CACHED_LAST_DISPOSITION_STATUS))) {
            return true;
        } else {
            return false;
        }
    }

    /** If this returns true we can be relaively sure that the file we want to download is a private file. */
    private boolean isAccountRequired(final Browser br) {
        if (br.getHost(true).equals("accounts.google.com")) {
            /* User is not logged in but file is private. */
            return true;
        } else if (br.getHttpConnection().getResponseCode() == 401) {
            return true;
        } else if (br.getHttpConnection().getResponseCode() == 403 && br.containsHTML("accounts\\.google\\.com/AccountChooser")) {
            /* User is logged in and file is private but user is lacking permissions to view file. */
            return true;
        } else {
            return false;
        }
    }

    public void handleErrorsAPI(final Browser br, final DownloadLink link, final Account account) throws PluginException {
        /*
         * E.g. {"error":{"errors":[{"domain":"global","reason":"downloadQuotaExceeded",
         * "message":"The download quota for this file has been exceeded."}],"code":403,
         * "message":"The download quota for this file has been exceeded."}}
         */
        /*
         * {"error":{"errors":[{"domain":"global","reason":"notFound","message":"File not found: <fileID>."
         * ,"locationType":"parameter","location":"fileId"}],"code":404,"message":"File not found: <fileID>."}}
         */
        /*
         * {"error":{"errors":[{"domain":"usageLimits","reason":"keyInvalid","message":"Bad Request"}],"code":400,"message":"Bad Request"}}
         */
        List<Map<String, Object>> errors = null;
        try {
            final Map<String, Object> entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
            errors = (List<Map<String, Object>>) JavaScriptEngineFactory.walkJson(entries, "error/errors");
        } catch (final Exception ignore) {
            /* Did not get the expected json response */
            logger.warning("Got unexpected API response");
            return;
        }
        if (errors == null || errors.isEmpty()) {
            return;
        }
        /* Most of all times there will be only one errort */
        logger.info("Number of detected errors: " + errors.size());
        int index = 0;
        for (final Map<String, Object> errormap : errors) {
            final boolean isLastItem = index == errors.size() - 1;
            final String reason = (String) errormap.get("reason");
            final String message = (String) errormap.get("message");
            /* First check for known issues */
            if (reason.equalsIgnoreCase("notFound")) {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            } else if (reason.equalsIgnoreCase("downloadQuotaExceeded")) {
                this.errorQuotaReachedInAPIMode(link, account);
            } else if (reason.equalsIgnoreCase("keyInvalid")) {
                throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "API key invalid", 3 * 60 * 60 * 1000l);
            } else if (reason.equalsIgnoreCase("cannotDownloadFile")) {
                this.errorCannotDownload(link);
            }
            /* Now either continue to the next error or handle it as unknown error if it's the last one in our Array of errors */
            logger.info("Unknown error detected: " + message);
            if (isLastItem) {
                if (link == null) {
                    /* Assume it's an account related error */
                    throw new AccountUnavailableException(message, 5 * 60 * 1000l);
                } else {
                    throw new PluginException(LinkStatus.ERROR_FATAL, message);
                }
            } else {
                index++;
            }
        }
    }

    private boolean isRateLimited(final Browser br) {
        if (br.getHttpConnection().getResponseCode() == 429) {
            return true;
        } else {
            return false;
        }
    }

    private void checkHandleRateLimit(final Browser br, final DownloadLink link, final Account account) throws PluginException, IOException, InterruptedException {
        if (isRateLimited(br)) {
            final boolean captchaRequired = CaptchaHelperHostPluginRecaptchaV2.containsRecaptchaV2Class(br);
            logger.info("Google rate-limit detected | captchaRequired =" + captchaRequired);
            if (link == null) {
                /* 2020-11-29: This captcha should never happen during account-check! It should only happen when requesting files. */
                if (captchaRequired) {
                    throw new AccountUnavailableException("Rate limited and captcha blocked", 5 * 60 * 1000l);
                } else {
                    throw new AccountUnavailableException("Rate limited", 5 * 60 * 1000l);
                }
            } else {
                /*
                 * 2020-09-09: Google is sometimes blocking users/whole ISP IP subnets so they need to go through this step in order to e.g.
                 * continue downloading.
                 */
                /*
                 * 2020-09-14: TODO: This handling doesn't work so we'll at least display a meaningful errormessage. The captcha should
                 * never occur anyways as upper handling will try to avoid it!
                 */
                if (!captchaRequired) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Rate limited");
                } else if (!canHandleGoogleSpecialCaptcha) {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Rate limited - captcha required but not implemented yet");
                }
                final Form captchaForm = br.getForm(0);
                if (captchaForm == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                final String recaptchaV2Response = new CaptchaHelperHostPluginRecaptchaV2(this, br).getToken();
                captchaForm.put("g-recaptcha-response", Encoding.urlEncode(recaptchaV2Response));
                /* This should now redirect back to where we initially wanted to got to! */
                // br.getHeaders().put("X-Client-Data", "0");
                br.submitForm(captchaForm);
                /* Double-check to make sure access was granted */
                if (this.isRateLimited(br)) {
                    logger.info("Captcha failed and/or rate-limit is still there");
                    /*
                     * Do not invalidate captcha result because most likely that was correct but our plugin somehow failed -> Try again
                     * later
                     */
                    throw new PluginException(LinkStatus.ERROR_HOSTER_TEMPORARILY_UNAVAILABLE, "429 too many requests: Captcha failed");
                } else {
                    logger.info("Captcha success");
                    if (account != null) {
                        /*
                         * Cookies have changed! Store new cookies so captcha won't happen again immediately. This is stored on the current
                         * session and not just IP!
                         */
                        account.saveCookies(br.getCookies(br.getHost()), "");
                    } else {
                        /*
                         * TODO: Consider to save- and restore session cookies - this captcha only has to be solved once per session per X
                         * time!
                         */
                    }
                }
            }
        }
    }

    /**
     * Use this for response 403 or messages like 'file can not be downloaded at this moment'. Such files will usually be downloadable via
     * account. </br>
     * Only use this for failed website download attempts!
     */
    private void errorDownloadQuotaReachedWebsite(final DownloadLink link, final Account account) throws PluginException {
        if (account != null) {
            if (this.isDownloadQuotaReachedAccount(link)) {
                errorQuotaReachedInAllModes(link);
            } else {
                link.setProperty(PROPERTY_IS_QUOTA_REACHED_ACCOUNT, System.currentTimeMillis());
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Download Quota reached: " + getDownloadQuotaReachedHint1(), getQuotaReachedWaittime());
            }
        } else {
            if (this.isDownloadQuotaReachedAccount(link)) {
                errorQuotaReachedInAllModes(link);
            } else {
                link.setProperty(PROPERTY_IS_QUOTA_REACHED_ANONYMOUS, System.currentTimeMillis());
                throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Download Quota reached: Try later or add Google account and retry", getQuotaReachedWaittime());
            }
        }
    }

    /** Use this for "Quota reached" errors during API download attempts. */
    private void errorQuotaReachedInAPIMode(final DownloadLink link, final Account account) throws PluginException {
        if (PluginJsonConfig.get(GoogleConfig.class).getAPIDownloadMode() == APIDownloadMode.WEBSITE_IF_ACCOUNT_AVAILABLE_AND_FILE_IS_QUOTA_LIMITED && account != null && !this.isDownloadQuotaReachedAccount(link)) {
            link.setProperty(PROPERTY_IS_QUOTA_REACHED_ANONYMOUS, System.currentTimeMillis());
            throw new PluginException(LinkStatus.ERROR_RETRY, "Retry with account in website mode to avoid 'Quota reached'");
        } else {
            link.setProperty(PROPERTY_IS_QUOTA_REACHED_ANONYMOUS, System.currentTimeMillis());
            if (account != null) {
                if (this.isDownloadQuotaReachedAccount(link)) {
                    errorQuotaReachedInAllModes(link);
                } else {
                    /* We haven't yet attempted to download this link via account. */
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Download Quota reached: Try later or adjust API download mode in plugin settings", getQuotaReachedWaittime());
                }
            } else {
                if (this.isDownloadQuotaReachedAccount(link)) {
                    errorQuotaReachedInAllModes(link);
                } else {
                    throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Download Quota reached: Try later or add Google account and retry", getQuotaReachedWaittime());
                }
            }
        }
    }

    /**
     * Use this if a link has been attempted to be downloaded with account and still wasn't downloadable.
     *
     * @throws PluginException
     */
    private void errorQuotaReachedInAllModes(final DownloadLink link) throws PluginException {
        throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Download Quota reached: " + getDownloadQuotaReachedHint1(), getQuotaReachedWaittime());
    }

    private void errorFileInfected(final DownloadLink link) throws PluginException {
        throw new PluginException(LinkStatus.ERROR_FATAL, "File is v" + "irus infected. Only file owner can download this file.");
    }

    private static String getDownloadQuotaReachedHint1() {
        return "Try later or import the file into your account and download it from there";
    }

    private static long getQuotaReachedWaittime() {
        return 2 * 60 * 60 * 1000;
    }

    /**
     * Use this for files which are not downloadable at all (rare case). </br>
     * This mostly gets called if a file is not downloadable according to the Google Drive API.
     */
    private void errorCannotDownload(final DownloadLink link) throws PluginException {
        String errorMsg = "Download not allowed!";
        if (this.videoStreamShouldBeAvailable(link)) {
            errorMsg += " Video stream download might be possible: Remove your API key, reset this file and try again.";
        } else {
            errorMsg += " If video streaming is available for this file, remove your Google Drive API key, reset this file and try again.";
        }
        throw new PluginException(LinkStatus.ERROR_FATAL, errorMsg);
    }

    private void errorGoogleDocumentDownloadImpossible() throws PluginException {
        throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "This Google Document is not downloadable or not available in desired format");
    }

    public void login(final Browser br, final Account account, final boolean forceLoginValidation) throws Exception {
        final boolean loginAPI = false;
        if (DebugMode.TRUE_IN_IDE_ELSE_FALSE && loginAPI) {
            loginAPI(br, account);
        } else {
            /* Website login */
            final GoogleHelper helper = new GoogleHelper(br, this.getLogger());
            helper.login(account, forceLoginValidation);
            final Cookies userCookies = account.loadUserCookies();
            if (forceLoginValidation && userCookies != null && PluginJsonConfig.get(GoogleConfig.class).isDebugAccountLogin()) {
                /* 2021-02-02: Testing advanced login-check for GDrive */
                final String cookieOSID = br.getCookie("google.com", "OSID");
                if (cookieOSID == null || cookieOSID.equals("")) {
                    logger.info("OSID cookie has empty value -> Checking if full value is present in user added cookies");
                    final Cookie realOSID = userCookies.get("OSID");
                    if (realOSID != null && realOSID.getValue().length() > 0) {
                        logger.info("OSID login workaround needed(?), real OSID cookie value is: " + realOSID.getValue());
                        br.setCookies(userCookies);
                    }
                }
                helper.validateCookiesGoogleDrive(br, account);
            }
        }
    }

    /**
     * 2021-02-02: Unfinished work! </br>
     * TODO: Add settings for apiID and apiSecret </br>
     */
    private void loginAPI(final Browser br, final Account account) throws IOException, InterruptedException, PluginException {
        /* https://developers.google.com/identity/protocols/oauth2/limited-input-device */
        br.setAllowedResponseCodes(new int[] { 428 });
        String access_token = account.getStringProperty(PROPERTY_ACCOUNT_ACCESS_TOKEN);
        int auth_expires_in = 0;
        String refresh_token = account.getStringProperty(PROPERTY_ACCOUNT_REFRESH_TOKEN);
        final long tokenTimeLeft = account.getLongProperty(PROPERTY_ACCOUNT_ACCESS_TOKEN_EXPIRE_TIMESTAMP, 0) - System.currentTimeMillis();
        Map<String, Object> entries = null;
        if (account.hasProperty(PROPERTY_ACCOUNT_ACCESS_TOKEN_EXPIRE_TIMESTAMP) && tokenTimeLeft <= 2 * 60 * 1000l) {
            logger.info("Token refresh required");
            final UrlQuery refreshTokenQuery = new UrlQuery();
            refreshTokenQuery.appendEncoded("client_id", getClientID());
            refreshTokenQuery.appendEncoded("client_secret", getClientSecret());
            refreshTokenQuery.appendEncoded("grant_type", refresh_token);
            refreshTokenQuery.appendEncoded("refresh_token", refresh_token);
            br.postPage("https://oauth2.googleapis.com/token", refreshTokenQuery);
            entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
            access_token = (String) entries.get("access_token");
            auth_expires_in = ((Number) entries.get("expires_in")).intValue();
            if (StringUtils.isEmpty(access_token)) {
                /* Permanently disable account */
                throw new AccountInvalidException("Token refresh failed");
            }
            logger.info("Successfully obtained new access_token");
            account.setProperty(PROPERTY_ACCOUNT_REFRESH_TOKEN, refresh_token);
            account.setProperty(PROPERTY_ACCOUNT_ACCESS_TOKEN_EXPIRE_TIMESTAMP, System.currentTimeMillis() + auth_expires_in * 1000l);
            br.getHeaders().put("Authorization", "Bearer " + access_token);
            return;
        } else if (access_token != null) {
            logger.info("Trust existing token without check");
            br.getHeaders().put("Authorization", "Bearer " + access_token);
            return;
        }
        logger.info("Performing full API login");
        final UrlQuery deviceCodeQuery = new UrlQuery();
        deviceCodeQuery.appendEncoded("client_id", getClientID());
        /*
         * We're using a recommended scope - we don't want to get permissions which we don't make use of:
         * https://developers.google.com/drive/api/v2/about-auth
         */
        deviceCodeQuery.appendEncoded("scope", "https://www.googleapis.com/auth/drive.file");
        br.postPage("https://oauth2.googleapis.com/device/code", deviceCodeQuery);
        entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
        final String device_code = (String) entries.get("device_code");
        final String user_code = (String) entries.get("user_code");
        final int user_code_expires_in = ((Number) entries.get("expires_in")).intValue();
        final int interval = ((Number) entries.get("interval")).intValue();
        final String verification_url = (String) entries.get("verification_url");
        int waitedSeconds = 0;
        /* 2020-12-15: Google allows the user to react within 30 minutes - we only allow 5. */
        int maxTotalSeconds = 5 * 60;
        if (user_code_expires_in < maxTotalSeconds) {
            maxTotalSeconds = user_code_expires_in;
        }
        final Thread dialog = showPINLoginInformation(verification_url, user_code);
        try {
            /* Polling */
            final UrlQuery pollingQuery = new UrlQuery();
            pollingQuery.appendEncoded("client_id", getClientID());
            pollingQuery.appendEncoded("client_secret", getClientSecret());
            pollingQuery.appendEncoded("device_code", device_code);
            pollingQuery.appendEncoded("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            do {
                Thread.sleep(interval * 1000l);
                br.postPage("https://oauth2.googleapis.com/token", pollingQuery);
                entries = JSonStorage.restoreFromString(br.toString(), TypeRef.HASHMAP);
                if (entries.containsKey("error")) {
                    logger.info("User hasn't yet confirmed auth");
                    continue;
                } else {
                    access_token = (String) entries.get("access_token");
                    refresh_token = (String) entries.get("refresh_token");
                    auth_expires_in = ((Number) entries.get("expires_in")).intValue();
                    break;
                }
            } while (waitedSeconds < maxTotalSeconds);
        } finally {
            dialog.interrupt();
        }
        if (StringUtils.isEmpty(access_token)) {
            throw new AccountInvalidException("Authorization failed");
        }
        br.getHeaders().put("Authorization", "Bearer " + access_token);
        account.setProperty(PROPERTY_ACCOUNT_ACCESS_TOKEN, access_token);
        account.setProperty(PROPERTY_ACCOUNT_REFRESH_TOKEN, refresh_token);
        account.setProperty(PROPERTY_ACCOUNT_ACCESS_TOKEN_EXPIRE_TIMESTAMP, System.currentTimeMillis() + auth_expires_in * 1000l);
    }

    private Thread showPINLoginInformation(final String pairingURL, final String confirmCode) {
        final Thread thread = new Thread() {
            public void run() {
                try {
                    String message = "";
                    final String title;
                    if ("de".equalsIgnoreCase(System.getProperty("user.language"))) {
                        title = "Google Drive - Login";
                        message += "Hallo liebe(r) Google Drive NutzerIn\r\n";
                        message += "Um deinen Google Drive Account in JDownloader verwenden zu können, musst du folgende Schritte beachten:\r\n";
                        message += "1. Öffne diesen Link im Browser falls das nicht automatisch passiert:\r\n\t'" + pairingURL + "'\t\r\n";
                        message += "2. Gib folgenden Code im Browser ein: " + confirmCode + "\r\n";
                        message += "Dein Account sollte nach einigen Sekunden von JDownloader akzeptiert werden.\r\n";
                    } else {
                        title = "Google Drive - Login";
                        message += "Hello dear Google Drive user\r\n";
                        message += "In order to use your Google Drive account in JDownloader, you need to follow these steps:\r\n";
                        message += "1. Open this URL in your browser if it is not opened automatically:\r\n\t'" + pairingURL + "'\t\r\n";
                        message += "2. Enter this confirmation code in your browser: " + confirmCode + "\r\n";
                        message += "Your account should be accepted in JDownloader within a few seconds.\r\n";
                    }
                    final ConfirmDialog dialog = new ConfirmDialog(UIOManager.LOGIC_COUNTDOWN, title, message);
                    dialog.setTimeout(5 * 60 * 1000);
                    if (CrossSystem.isOpenBrowserSupported() && !Application.isHeadless()) {
                        CrossSystem.openURL(pairingURL);
                    }
                    final ConfirmDialogInterface ret = UIOManager.I().show(ConfirmDialogInterface.class, dialog);
                    ret.throwCloseExceptions();
                } catch (final Throwable e) {
                    getLogger().log(e);
                }
            };
        };
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    public static final boolean canUseAPI() {
        if (StringUtils.isEmpty(getAPIKey())) {
            return false;
        } else {
            return true;
        }
    }

    public static final String getAPIKey() {
        return PluginJsonConfig.get(GoogleConfig.class).getGoogleDriveAPIKey();
    }

    public static final String getClientID() {
        return null;
        // return "blah";
    }

    public static final String getClientSecret() {
        return null;
        // return "blah";
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final AccountInfo ai = new AccountInfo();
        login(br, account, true);
        ai.setUnlimitedTraffic();
        account.setType(AccountType.FREE);
        account.setConcurrentUsePossible(true);
        account.setMaxSimultanDownloads(-1);
        return ai;
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        handleDownload(link, account);
    }

    private void removeQuotaReachedFlags(final DownloadLink link, final Account account) {
        if (account != null) {
            link.removeProperty(PROPERTY_IS_QUOTA_REACHED_ACCOUNT);
            link.removeProperty(PROPERTY_IS_STREAM_QUOTA_REACHED_ACCOUNT);
            return;
        }
        /* No account = Remove all quota_reached properties. */
        link.removeProperty(PROPERTY_IS_QUOTA_REACHED_ANONYMOUS);
        link.removeProperty(PROPERTY_IS_STREAM_QUOTA_REACHED_ANONYMOUS);
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(final DownloadLink link) {
        if (link != null) {
            link.setProperty("ServerComaptibleForByteRangeRequest", true);
            link.removeProperty(PROPERTY_USED_QUALITY);
            link.removeProperty(PROPERTY_CAN_DOWNLOAD);
            link.removeProperty(PROPERTY_IS_QUOTA_REACHED_ACCOUNT);
            link.removeProperty(PROPERTY_IS_QUOTA_REACHED_ANONYMOUS);
            link.removeProperty(PROPERTY_IS_STREAM_QUOTA_REACHED_ACCOUNT);
            link.removeProperty(PROPERTY_IS_STREAM_QUOTA_REACHED_ANONYMOUS);
        }
    }

    @Override
    public Class<? extends PluginConfigInterface> getConfigInterface() {
        return GoogleConfig.class;
    }
}