package jd.plugins.hoster;

import java.io.IOException;

import jd.PluginWrapper;
import jd.config.SubConfiguration;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "hdd.tomsk.ru" }, urls = { "https?://(?:www|download\\.)?hdd\\.tomsk\\.ru/file/(?!notfound)([a-z]{8})" })
public class HddTomskRu extends PluginForHost {
    public static final String  DOMAIN              = "http://hdd.tomsk.ru";
    public static final String  HDDSID              = "HDDSID";
    public static final String  NOTFOUND            = "http://hdd.tomsk.ru/file/notfound";
    public static final String  TERMS               = "http://hdd.tomsk.ru/terms";
    public static final String  TERMS_ACCEPT        = "http://hdd.tomsk.ru/?rm=terms_accept";
    private static final String FILE_ENTER_PASSWORD = "http://hdd.tomsk.ru/?rm=file_enter_password";
    private static final String PWTEXT              = ">Для доступа к файлу необходим пароль<";
    private String              DLLINK              = null;

    /**
     * @author rnw
     */
    public HddTomskRu(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public String getAGBLink() {
        return DOMAIN + "/terms";
    }

    public boolean hasCaptcha(DownloadLink link, jd.plugins.Account acc) {
        return false;
    }

    @Override
    public String getLinkID(final DownloadLink link) {
        final String fid = getFID(link);
        if (fid != null) {
            return this.getHost() + "://" + fid;
        } else {
            return super.getLinkID(link);
        }
    }

    private String getFID(final DownloadLink link) {
        return new Regex(link.getPluginPatternMatcher(), this.getSupportedLinks()).getMatch(0);
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        this.setBrowserExclusive();
        final SubConfiguration pluginConfig = getPluginConfig();
        String hddSid = pluginConfig.getStringProperty(HDDSID);
        if (hddSid != null) {
            br.setCookie(DOMAIN, HDDSID, hddSid);
        }
        final String fileId = this.getFID(link);
        br.getPage(link.getPluginPatternMatcher().replace("http://", "https://"));
        if (br.getHttpConnection().getResponseCode() == 404 || !br.getURL().contains(fileId)) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        final String fileTitle = "hdd.tomsk.ru - Файл " + fileId;
        String redirectLocation = br.getRedirectLocation();
        /* No such file */
        if (redirectLocation != null && !redirectLocation.contains(fileId)) {
            throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        }
        /* Password protected file */
        if (redirectLocation == null && br.containsHTML(PWTEXT)) {
            for (int i = 0; i <= 3; i++) {
                final String passCode = Plugin.getUserInput("Enter password for: " + fileTitle, link);
                br.postPage(FILE_ENTER_PASSWORD, "password=" + Encoding.urlEncode(passCode) + "&signature=" + fileId);
                if (br.getRedirectLocation() != null) {
                    br.getPage(br.getRedirectLocation());
                }
                if (br.containsHTML(PWTEXT)) {
                    continue;
                }
                break;
            }
            if (br.containsHTML(PWTEXT)) {
                throw new PluginException(LinkStatus.ERROR_RETRY, "Wrong password entered");
            }
            redirectLocation = br.getRedirectLocation();
        }
        /* Accept TOS */
        if (redirectLocation != null && redirectLocation.equals(TERMS)) {
            br.postPage(TERMS_ACCEPT, "accept=1");
            if (br.getRedirectLocation() != null) {
                br.getPage(br.getRedirectLocation());
                String cookie = br.getCookie(DOMAIN, HDDSID);
                if (cookie != null) {
                    pluginConfig.setProperty(HDDSID, cookie);
                    pluginConfig.save();
                }
            }
        }
        final Regex linkRegex = br.getRegex("<a href=\"(http://download\\.hdd\\.tomsk\\.ru/download/" + fileId + "\\?[a-f0-9]{32})\" title=[^>]+>(.*?)</a>");
        DLLINK = linkRegex.getMatch(0);
        if (DLLINK == null) {
            DLLINK = br.getRegex("(http://download\\.hdd\\.tomsk\\.ru/download/" + fileId + "\\?[a-f0-9]{32})").getMatch(0);
            if (DLLINK == null) {
                throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            }
        }
        String finalFileName = linkRegex.getMatch(1);
        if (finalFileName == null) {
            finalFileName = br.getRegex("<title>hdd\\.tomsk\\.ru &mdash; (.*?)</title>").getMatch(0);
        }
        URLConnectionAdapter con = null;
        try {
            con = br.openGetConnection(DLLINK);
            if (this.looksLikeDownloadableContent(con)) {
                link.setDownloadSize(con.getCompleteContentLength());
                if (finalFileName != null) {
                    link.setFinalFileName(finalFileName);
                }
            } else {
                throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
            }
            return AvailableStatus.TRUE;
        } finally {
            try {
                con.disconnect();
            } catch (Throwable e) {
            }
        }
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception {
        requestFileInformation(link);
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, DLLINK, true, 0);
        if (dl.getConnection().getContentType().contains("html")) {
            try {
                br.followConnection(true);
            } catch (final IOException e) {
                logger.log(e);
            }
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}