package uk.co.gresearch.siembol.configeditor.configstore;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.gresearch.siembol.configeditor.common.ConfigInfoProvider;
import uk.co.gresearch.siembol.configeditor.common.UserInfo;
import uk.co.gresearch.siembol.configeditor.git.GitRepository;
import uk.co.gresearch.siembol.configeditor.model.ConfigEditorAttributes;
import uk.co.gresearch.siembol.configeditor.model.ConfigEditorFile;
import uk.co.gresearch.siembol.configeditor.model.ConfigEditorResult;
import uk.co.gresearch.siembol.configeditor.common.ConfigInfo;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import static uk.co.gresearch.siembol.configeditor.model.ConfigEditorResult.StatusCode.BAD_REQUEST;
import static uk.co.gresearch.siembol.configeditor.model.ConfigEditorResult.StatusCode.OK;

public class ConfigItems {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final String INIT_START = "Trying Initialise a git repository: {}";
    private static final String INIT_COMPLETED = "Initialisation of a git repository completed";
    private static final String INVALID_CONFIG_VERSION = "Invalid config version %d in config %s";
    private static final String INIT_ERROR_MSG = "Problem during initialisation of config items";
    private static final String UPDATE_INIT_LOG_MSG = "User {} requested to add/update {} name: {} to version: {}";
    private static final String UPDATE_COMPLETED_LOG_MSG = "{} name: {} to version: {} update completed";
    private static final int NEW_CONFIG_EXPECTED_VERSION = 0;

    private final String directory;
    private final GitRepository gitRepository;
    private final ConfigInfoProvider configInfoProvider;

    private final Map<String, Integer> versions = new HashMap<>();
    private final AtomicReference<List<ConfigEditorFile>> filesCache = new AtomicReference<>();

    public ConfigItems(GitRepository gitRepository,
                       ConfigInfoProvider configInfoProvider,
                       String directory) {
        this.directory = directory;
        this.gitRepository = gitRepository;
        this.configInfoProvider = configInfoProvider;
    }

    private boolean checkVersion(ConfigInfo itemInfo) {
        String name = itemInfo.getName();
        return versions.getOrDefault(name, NEW_CONFIG_EXPECTED_VERSION) == itemInfo.getOldVersion();
    }

    public void init() throws IOException, GitAPIException {
        LOG.info(INIT_START, gitRepository.getRepoUri());
        ConfigEditorResult result = gitRepository.getFiles(directory, configInfoProvider::isStoreFile);
        if (result.getStatusCode() != OK) {
            throw new IllegalStateException(INIT_ERROR_MSG);
        }

        filesCache.set(result.getAttributes().getFiles());
        result.getAttributes().getFiles().forEach(x -> {
            ConfigInfo info = configInfoProvider.getConfigInfo(x.getContent());
            versions.put(info.getName(), info.getOldVersion());
        });

        LOG.info(INIT_COMPLETED);
    }

    public ConfigEditorResult getFiles() {
        List<ConfigEditorFile> files = filesCache.get();
        ConfigEditorAttributes attributes = new ConfigEditorAttributes();
        attributes.setFiles(files);
        return new ConfigEditorResult(OK, attributes);
    }

    private ConfigEditorResult updateConfigItemInternally(UserInfo user,
                                                          String configItem,
                                                          boolean shouldBeNew) throws GitAPIException, IOException {
        ConfigInfo configInfo = configInfoProvider.getConfigInfo(user, configItem);
        LOG.info(UPDATE_INIT_LOG_MSG,
                user.getUserName(),
                configInfo.getConfigInfoType().getSingular(),
                configInfo.getName(),
                configInfo.getVersion());

        if (shouldBeNew != configInfo.isNewConfig()
                || !checkVersion(configInfo)) {
            String msg = String.format(INVALID_CONFIG_VERSION, configInfo.getOldVersion(), configInfo.getName());
            LOG.info(msg);
            return ConfigEditorResult.fromMessage(BAD_REQUEST, msg);
        }

        ConfigEditorResult result = gitRepository.transactCopyAndCommit(configInfo,
                directory,
                configInfoProvider::isStoreFile);
        if (result.getStatusCode() == OK) {
            filesCache.set(result.getAttributes().getFiles());
            versions.put(configInfo.getName(), configInfo.getVersion());
            LOG.info(UPDATE_COMPLETED_LOG_MSG,
                    configInfo.getConfigInfoType().getSingular(),
                    configInfo.getName(),
                    configInfo.getVersion());
        }
        return result;
    }

    public ConfigEditorResult updateConfigItem(UserInfo user, String configItem) throws GitAPIException, IOException {
        return updateConfigItemInternally(user, configItem, false);
    }

    public ConfigEditorResult addConfigItem(UserInfo user, String configItem) throws GitAPIException, IOException {
        return updateConfigItemInternally(user, configItem, true);
    }

    public String getRepoUri() {
        return gitRepository.getRepoUri();
    }

    public String getDirectoryUri() {
        return gitRepository.getDirectoryUrl(directory);
    }
}
