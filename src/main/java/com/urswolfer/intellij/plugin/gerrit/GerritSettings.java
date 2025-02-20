/*
 * Copyright 2000-2012 JetBrains s.r.o.
 * Copyright 2013 Urs Wolfer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.urswolfer.intellij.plugin.gerrit;

import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.intellij.ide.DataManager;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.passwordSafe.PasswordSafeException;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.urswolfer.gerrit.client.rest.GerritAuthData;
import com.urswolfer.intellij.plugin.gerrit.ui.ShowProjectColumn;
import org.apache.commons.lang.BooleanUtils;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Parts based on org.jetbrains.plugins.github.GithubSettings
 *
 * @author oleg
 * @author Urs Wolfer
 */
@State(name = "GerritSettings", storages = @Storage("gerrit_settings.xml"))
public class GerritSettings implements PersistentStateComponent<Element>, GerritAuthData {

    private static final String GERRIT_SETTINGS_TAG = "GerritSettings";
    private static final String PROJECT_LIST_TAG = "projects";
    private static final String PROJECT_TAG = "project";
    private static final String GERRIT_SETTINGS_PASSWORD_KEY = "GERRIT_SETTINGS_PASSWORD_KEY";

    private Map<String, String> preloadedPasswords = new HashMap<String, String>();
    private Map<String, GerritSettingsData> projectSettings = new HashMap<String, GerritSettingsData>();

    private Logger log;

    public Element getState() {
        // Handle global settings separately for backwards compatibility
        GerritSettingsData globalSettings = projectSettings.get(GERRIT_SETTINGS_TAG);
        final Element mainElement = new Element(GERRIT_SETTINGS_TAG);
        globalSettings.fillElement(mainElement,GERRIT_SETTINGS_TAG);

        // If we don't have project settings yet, default to global settings.
        String currentProjectName = getCurrentProjectName();
        if(!currentProjectName.isEmpty()) {
            if(!projectSettings.containsKey(currentProjectName)) {
                addProjectSettingsFromDefault(currentProjectName);
            }
        }

        Element projects = new Element(PROJECT_LIST_TAG);
        projectSettings.forEach((projectName, settings) -> {
            if(projectName!=null && !projectName.equals(GERRIT_SETTINGS_TAG)) {
                Element project = new Element(PROJECT_TAG);
                projects.addContent(settings.fillElement(project,projectName));
            }
        });
        mainElement.addContent(projects);

        return mainElement;
    }

    public void loadState(@NotNull final Element element) {
        // All the logic on retrieving password was moved to getPassword action to cleanup initialization process
        try {
            // Load global settings
            projectSettings.put(GERRIT_SETTINGS_TAG, new GerritSettingsData(element, log));

            Element projectDom = element.getChild(PROJECT_LIST_TAG);
            if(projectDom != null) {
                List<Element> projectSettingsElements = projectDom.getChildren();
                for (Element projectSettingsElement : projectSettingsElements) {
                    String projectName = projectSettingsElement.getAttributeValue(GerritSettingsData.NAME);
                    if (!Strings.isNullOrEmpty(projectName)) {
                        projectSettings.put(projectName, new GerritSettingsData(projectSettingsElement, log));
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error happened while loading gerrit settings: " + e);
        }
    }

    private String getCurrentProjectName() {
        Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
        if(openProjects.length == 1) {
            return openProjects[0].getName();
        }

        // If there are multiple projects open, try to get the project from the focus.
        try{
            DataContext context = DataManager.getInstance().getDataContextFromFocus().getResult();
            if(context != null) {
                Project currentProject = context.getData(CommonDataKeys.PROJECT);
                if(currentProject != null) {
                    return currentProject.getName();
                }
            }
        } catch (Exception e) {
            // Project may just not be loaded yet.
        }
        return "";
    }

    private void addProjectSettingsFromDefault(String projectName){
        // Default to global settings
        Element globalSettings = getDefaultSettings().fillElement(new Element(GERRIT_SETTINGS_TAG),GERRIT_SETTINGS_TAG);
        projectSettings.put(projectName, new GerritSettingsData(globalSettings, log));
        setPassword(getDefaultPassword());
    }

    public GerritSettingsData getDefaultSettings(){
        return projectSettings.get(GERRIT_SETTINGS_TAG);
    }

    public <T> T getForCurrentProject(Function<GerritSettingsData, T> method) {
        String projectName = getCurrentProjectName();
        if(!projectName.isEmpty()) {
            if(!projectSettings.containsKey(projectName)) {
                addProjectSettingsFromDefault(projectName);
            }
            GerritSettingsData settings = projectSettings.get(projectName);
            if(settings != null) {
                return method.apply(settings);
            }
        }
        return null;
    }
    public void setForCurrentProject(Consumer<GerritSettingsData> method) {
        String projectName = getCurrentProjectName();
        if(!projectName.isEmpty()) {
            if(!projectSettings.containsKey(projectName)) {
                addProjectSettingsFromDefault(projectName);
            }
            GerritSettingsData settings = projectSettings.get(projectName);
            if(settings != null) {
                method.accept(settings);
            }
        }
    }

    @Override
    @Nullable
    public String getLogin() {
        return getForCurrentProject(GerritSettingsData::getLogin);
    }

    @Override
    @NotNull
    public String getPassword() {
        String currentProject = getCurrentProjectName();
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            if (!preloadedPasswords.containsKey(currentProject)) {
                throw new IllegalStateException("Need to call #preloadPassword when password is required in background thread");
            }
        } else {
            preloadPassword();
        }
        return preloadedPasswords.get(currentProject);
    }

    public void preloadPassword() {
        String password = null;
        try {
            password = PasswordSafe.getInstance().getPassword(null, GerritSettings.class, getPasswordKey());
        } catch (PasswordSafeException e) {
            log.info("Couldn't get password for key [" + getPasswordKey() + "]", e);
        }
        preloadedPasswords.put(getCurrentProjectName(), password);
    }

    public String getDefaultPassword() {
        try {
            return PasswordSafe.getInstance().getPassword(null, GerritSettings.class, GERRIT_SETTINGS_PASSWORD_KEY);
        } catch (PasswordSafeException e) {
            log.info("Couldn't get password for key [" + GERRIT_SETTINGS_PASSWORD_KEY + "]", e);
        }
        return "";
    }

    public void setDefaultPassword(String password){
        try {
            PasswordSafe.getInstance().storePassword(null, GerritSettings.class, GERRIT_SETTINGS_PASSWORD_KEY, password != null ? password : "");
        } catch (PasswordSafeException e) {
            log.info("Couldn't set password for key [" + GERRIT_SETTINGS_PASSWORD_KEY + "]", e);
        }
    }

    private String getPasswordKey() {
        final String projectName = getCurrentProjectName();
        if (!projectName.isEmpty()) {
            return Base64.getEncoder().encodeToString(projectName.getBytes()).replace("=", "");
        }
        return GERRIT_SETTINGS_PASSWORD_KEY;
    }

    @Override
    public boolean isHttpPassword() {
        return false;
    }

    @Override
    public String getHost() {
        String host = getForCurrentProject(GerritSettingsData::getHost);
        return host != null ? host : "";
    }

    @Override
    public boolean isLoginAndPasswordAvailable() {
        return !Strings.isNullOrEmpty(getLogin());
    }

    public boolean getListAllChanges() {
        return BooleanUtils.isTrue(getForCurrentProject(GerritSettingsData::getListAllChanges));
    }

    public void setListAllChanges(boolean listAllChanges) {
        setForCurrentProject(settings -> {
            settings.setListAllChanges(listAllChanges);
        });
    }

    public boolean getAutomaticRefresh() {
        return BooleanUtils.isTrue(getForCurrentProject(GerritSettingsData::getAutomaticRefresh));
    }

    public int getRefreshTimeout() {
        Integer timeout = getForCurrentProject(GerritSettingsData::getRefreshTimeout);
        return timeout != null ? timeout : 0;
    }

    public boolean getReviewNotifications() {
        return BooleanUtils.isTrue(getForCurrentProject(GerritSettingsData::getReviewNotifications));
    }

    public void setLogin(final String login) {
        setForCurrentProject(settings -> {
            settings.setLogin(login);
        });
    }

    public void setPassword(final String password) {
        try {
            PasswordSafe.getInstance().storePassword(null, GerritSettings.class, getPasswordKey(), password != null ? password : "");
        } catch (PasswordSafeException e) {
            log.info("Couldn't set password for key [" + getPasswordKey() + "]", e);
        }
    }

    public void forgetPassword() {
        try {
            PasswordSafe.getInstance().removePassword(null, GerritSettings.class, getPasswordKey());
        } catch (PasswordSafeException e) {
            log.info("Couldn't forget password for key [" + getPasswordKey() + "]", e);
        }
    }

    public void setHost(final String host) {
        setForCurrentProject(settings -> {
            settings.setHost(host);
        });
    }

    public void setAutomaticRefresh(final boolean automaticRefresh) {
        setForCurrentProject(settings -> {
            settings.setAutomaticRefresh(automaticRefresh);
        });
    }

    public void setRefreshTimeout(final int refreshTimeout) {
        setForCurrentProject(settings -> {
            settings.setRefreshTimeout(refreshTimeout);
        });
    }

    public void setReviewNotifications(final boolean reviewNotifications) {
        setForCurrentProject(settings -> {
            settings.setReviewNotifications(reviewNotifications);
        });
    }

    public void setPushToGerrit(boolean pushToGerrit) {
        setForCurrentProject(settings -> {
            settings.setPushToGerrit(pushToGerrit);
        });
    }

    public boolean getPushToGerrit() {
        return BooleanUtils.isTrue(getForCurrentProject(GerritSettingsData::getPushToGerrit));
    }

    public boolean getShowChangeNumberColumn() {
        return BooleanUtils.isTrue(getForCurrentProject(GerritSettingsData::getShowChangeNumberColumn));
    }

    public void setShowChangeNumberColumn(boolean showChangeNumberColumn) {
        setForCurrentProject(settings -> {
            settings.setShowChangeNumberColumn(showChangeNumberColumn);
        });
    }

    public boolean getShowChangeIdColumn() {
        return BooleanUtils.isTrue(getForCurrentProject(GerritSettingsData::getShowChangeIdColumn));
    }

    public void setShowChangeIdColumn(boolean showChangeIdColumn) {
        setForCurrentProject(settings -> {
            settings.setShowChangeIdColumn(showChangeIdColumn);
        });
    }

    public boolean getShowTopicColumn() {
        return BooleanUtils.isTrue(getForCurrentProject(GerritSettingsData::getShowTopicColumn));
    }

    public ShowProjectColumn getShowProjectColumn() {
        ShowProjectColumn showProjectColumn = getForCurrentProject(GerritSettingsData::getShowProjectColumn);
        return showProjectColumn != null ? showProjectColumn : ShowProjectColumn.AUTO;
    }

    public void setShowProjectColumn(ShowProjectColumn showProjectColumn) {
        setForCurrentProject(settings -> {
            settings.setShowProjectColumn(showProjectColumn);
        });
    }

    public void setShowTopicColumn(boolean showTopicColumn) {
        setForCurrentProject(settings -> {
            settings.setShowTopicColumn(showTopicColumn);
        });
    }

    public void setCloneBaseUrl(String cloneBaseUrl) {
        setForCurrentProject(settings -> {
            settings.setCloneBaseUrl(cloneBaseUrl);
        });
    }

    public String getCloneBaseUrl() {
        return getForCurrentProject(GerritSettingsData::getCloneBaseUrl);
    }

    public void setLog(Logger log) {
        // Set for both here and the current project
        this.log = log;
        setForCurrentProject(settings -> {
            settings.setLog(log);
        });
    }

    public String getCloneBaseUrlOrHost() {
        String urlOrHost = getForCurrentProject(GerritSettingsData::getCloneBaseUrlOrHost);
        return urlOrHost != null ? urlOrHost : "";
    }
}
