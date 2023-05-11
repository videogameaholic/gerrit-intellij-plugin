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
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.urswolfer.gerrit.client.rest.GerritAuthData;
import com.urswolfer.intellij.plugin.gerrit.ui.ShowProjectColumn;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parts based on org.jetbrains.plugins.github.GithubSettings
 *
 * @author oleg
 * @author Urs Wolfer
 */
@State(name = "GerritSettings", storages = @Storage("gerrit_settings.xml"))
public class GerritSettings implements PersistentStateComponent<Element>, GerritAuthData {

    private static final String GERRIT_SETTINGS_TAG = "GerritSettings";
    private static final String GERRIT_SETTINGS_PASSWORD_KEY = "GERRIT_SETTINGS_PASSWORD_KEY";

    private Map<String, String> preloadedPasswords = new HashMap<String, String>();

    private Map<String, GerritSettingsData> projectSettings = new HashMap<String, GerritSettingsData>();

    private Logger log;

    public Element getState() {
        // Handle global settings separately for backwards compatibility
        GerritSettingsData globalSettings = projectSettings.get(GERRIT_SETTINGS_TAG);
        final Element element = globalSettings.getAsElement(GERRIT_SETTINGS_TAG);

        // If we don't have project settings yet, default to global settings.
        String currentProjectName = getCurrentProjectName();
        if(!currentProjectName.isEmpty()) {
            if(!projectSettings.containsKey(currentProjectName)) {
                addProjectSettingsFromDefault(currentProjectName);
            }
        }

        Element projects = new Element("Projects");
        projectSettings.forEach((projectName, settings) -> {
            if(projectName!=null && !projectName.equals(GERRIT_SETTINGS_TAG)) {
                final Element projectElement = settings.getAsElement(projectName);
                projects.addContent(projectElement);
            }
        });
        element.addContent(projects);

        return element;
    }

    public void loadState(@NotNull final Element element) {
        // All the logic on retrieving password was moved to getPassword action to cleanup initialization process
        try {
            // Load global settings
            projectSettings.put(GERRIT_SETTINGS_TAG, new GerritSettingsData(element, log));

            List<Element> projectSettingsElements = element.getChild("Projects").getChildren();
            for (Element projectSettingsElement : projectSettingsElements) {
                String projectName = projectSettingsElement.getAttributeValue("name");
                if(!projectName.isEmpty()) {
                    projectSettings.put(projectName, new GerritSettingsData(projectSettingsElement, log));
                }
            }

        } catch (Exception e) {
            log.error("Error happened while loading gerrit settings: " + e);
        }
    }

    private String getCurrentProjectName() {
        try{
            Project thisProject = getCurrentProject();
            if(thisProject != null) {
                return thisProject.getName();
            }
        } catch (Exception e) {
            // Project may just not be loaded yet.
        }
        return "";
    }

    private Project getCurrentProject() {
        try{
            DataContext context = DataManager.getInstance().getDataContextFromFocus().getResult();
            if(context != null) {
                return DataKeys.PROJECT.getData(context);
            }
        } catch (Exception e) {
            // Project may just not be loaded yet.
        }
        return null;
    }

    private GerritSettingsData getProjectSettingsOrDefault(){
        String currentProjectName = getCurrentProjectName();
        if(!currentProjectName.isEmpty()) {
            if(!projectSettings.containsKey(currentProjectName)) {
                addProjectSettingsFromDefault(currentProjectName);
            }
            return projectSettings.get(currentProjectName);
        }
        return getDefaultSettings();
    }

    public GerritSettingsData getDefaultSettings(){
        return projectSettings.get(GERRIT_SETTINGS_TAG);
    }

    private void addProjectSettingsFromDefault(String projectName){
        // Default to global settings
        Element globalSettings = projectSettings.get(GERRIT_SETTINGS_TAG).getAsElement(GERRIT_SETTINGS_TAG);
        projectSettings.put(projectName, new GerritSettingsData(globalSettings, log));
        setPassword(getDefaultPassword());
    }

    @Override
    @Nullable
    public String getLogin() {
        return getProjectSettingsOrDefault().getLogin();
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
            return GERRIT_SETTINGS_PASSWORD_KEY + "_" + Base64.getEncoder().encodeToString(projectName.getBytes());
        }
        return GERRIT_SETTINGS_PASSWORD_KEY;
    }

    @Override
    public boolean isHttpPassword() {
        return false;
    }

    @Override
    public String getHost() {
        return getProjectSettingsOrDefault().getHost();
    }

    @Override
    public boolean isLoginAndPasswordAvailable() {
        return !Strings.isNullOrEmpty(getLogin());
    }

    public boolean getListAllChanges() {
        return getProjectSettingsOrDefault().getListAllChanges();
    }

    public void setListAllChanges(boolean listAllChanges) {
        getProjectSettingsOrDefault().setListAllChanges(listAllChanges);
    }

    public boolean getAutomaticRefresh() {
        return getProjectSettingsOrDefault().getAutomaticRefresh();
    }

    public int getRefreshTimeout() {
        return getProjectSettingsOrDefault().getRefreshTimeout();
    }

    public boolean getReviewNotifications() {
        return getProjectSettingsOrDefault().getReviewNotifications();
    }

    public void setLogin(final String login) {
        getProjectSettingsOrDefault().setLogin(login);
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
        getProjectSettingsOrDefault().setHost(host);
    }

    public void setAutomaticRefresh(final boolean automaticRefresh) {
        getProjectSettingsOrDefault().setAutomaticRefresh(automaticRefresh);
    }

    public void setRefreshTimeout(final int refreshTimeout) {
        getProjectSettingsOrDefault().setRefreshTimeout(refreshTimeout);
    }

    public void setReviewNotifications(final boolean reviewNotifications) {
        getProjectSettingsOrDefault().setReviewNotifications(reviewNotifications);
    }

    public void setPushToGerrit(boolean pushToGerrit) {
        getProjectSettingsOrDefault().setPushToGerrit(pushToGerrit);
    }

    public boolean getPushToGerrit() {
        return getProjectSettingsOrDefault().getPushToGerrit();
    }

    public boolean getShowChangeNumberColumn() {
        return getProjectSettingsOrDefault().getShowChangeNumberColumn();
    }

    public void setShowChangeNumberColumn(boolean showChangeNumberColumn) {
        getProjectSettingsOrDefault().setShowChangeNumberColumn(showChangeNumberColumn);
    }

    public boolean getShowChangeIdColumn() {
        return getProjectSettingsOrDefault().getShowChangeIdColumn();
    }

    public void setShowChangeIdColumn(boolean showChangeIdColumn) {
        getProjectSettingsOrDefault().setShowChangeIdColumn(showChangeIdColumn);
    }

    public boolean getShowTopicColumn() {
        return getProjectSettingsOrDefault().getShowTopicColumn();
    }

    public ShowProjectColumn getShowProjectColumn() {
        return getProjectSettingsOrDefault().getShowProjectColumn();
    }

    public void setShowProjectColumn(ShowProjectColumn showProjectColumn) {
        getProjectSettingsOrDefault().setShowProjectColumn(showProjectColumn);
    }

    public void setShowTopicColumn(boolean showTopicColumn) {
        getProjectSettingsOrDefault().setShowTopicColumn(showTopicColumn);
    }

    public void setCloneBaseUrl(String cloneBaseUrl) {
        getProjectSettingsOrDefault().setCloneBaseUrl(cloneBaseUrl);
    }

    public String getCloneBaseUrl() {
        return getProjectSettingsOrDefault().getCloneBaseUrl();
    }

    public void setLog(Logger log) {
        this.log = log;
    }

    public String getCloneBaseUrlOrHost() {
        return getProjectSettingsOrDefault().getCloneBaseUrlOrHost();
    }

}
