package io.notifer.jenkins;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.Extension;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.verb.POST;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;

/**
 * Global configuration for Notifer plugin.
 * Accessible at Manage Jenkins > Configure System > Notifer.
 */
@Extension
public class NotiferGlobalConfiguration extends GlobalConfiguration {

    private String serverUrl = "https://app.notifer.io";
    private String defaultCredentialsId;
    private String defaultTopic;
    private int defaultPriority = 3;

    /**
     * Constructor - loads saved configuration.
     */
    public NotiferGlobalConfiguration() {
        load();
    }

    /**
     * Get the singleton instance.
     */
    @NonNull
    public static NotiferGlobalConfiguration get() {
        NotiferGlobalConfiguration config = GlobalConfiguration.all().get(NotiferGlobalConfiguration.class);
        if (config == null) {
            throw new IllegalStateException("NotiferGlobalConfiguration not found");
        }
        return config;
    }

    @NonNull
    @Override
    public String getDisplayName() {
        return "Notifer";
    }

    // --- Getters and Setters ---

    public String getServerUrl() {
        return serverUrl;
    }

    @DataBoundSetter
    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
        save();
    }

    public String getDefaultCredentialsId() {
        return defaultCredentialsId;
    }

    @DataBoundSetter
    public void setDefaultCredentialsId(String defaultCredentialsId) {
        this.defaultCredentialsId = defaultCredentialsId;
        save();
    }

    public String getDefaultTopic() {
        return defaultTopic;
    }

    @DataBoundSetter
    public void setDefaultTopic(String defaultTopic) {
        this.defaultTopic = defaultTopic;
        save();
    }

    public int getDefaultPriority() {
        return defaultPriority;
    }

    @DataBoundSetter
    public void setDefaultPriority(int defaultPriority) {
        this.defaultPriority = Math.max(1, Math.min(5, defaultPriority));
        save();
    }

    // --- Form Validation ---

    /**
     * Validate server URL.
     */
    public FormValidation doCheckServerUrl(@QueryParameter String value) {
        if (value == null || value.isEmpty()) {
            return FormValidation.error("Server URL is required");
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return FormValidation.error("Server URL must start with http:// or https://");
        }
        return FormValidation.ok();
    }

    /**
     * Validate priority.
     */
    public FormValidation doCheckDefaultPriority(@QueryParameter int value) {
        if (value < 1 || value > 5) {
            return FormValidation.error("Priority must be between 1 and 5");
        }
        return FormValidation.ok();
    }

    /**
     * Fill credentials dropdown.
     */
    public ListBoxModel doFillDefaultCredentialsIdItems(
            @AncestorInPath Item item,
            @QueryParameter String credentialsId) {

        StandardListBoxModel result = new StandardListBoxModel();

        if (item == null) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return result.includeCurrentValue(credentialsId);
            }
        } else {
            if (!item.hasPermission(Item.EXTENDED_READ)
                    && !item.hasPermission(CredentialsProvider.USE_ITEM)) {
                return result.includeCurrentValue(credentialsId);
            }
        }

        return result
                .includeEmptyValue()
                .includeMatchingAs(
                        item instanceof hudson.model.Queue.Task
                                ? ((hudson.model.Queue.Task) item).getDefaultAuthentication()
                                : ACL.SYSTEM,
                        item,
                        StringCredentials.class,
                        URIRequirementBuilder.fromUri(serverUrl).build(),
                        CredentialsMatchers.always()
                )
                .includeCurrentValue(credentialsId);
    }

    /**
     * Test connection to Notifer server.
     */
    @POST
    @RequirePOST
    public FormValidation doTestConnection(
            @QueryParameter String serverUrl,
            @QueryParameter String defaultCredentialsId,
            @QueryParameter String defaultTopic) {

        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        if (serverUrl == null || serverUrl.isEmpty()) {
            return FormValidation.error("Server URL is required");
        }

        if (defaultTopic == null || defaultTopic.isEmpty()) {
            return FormValidation.error("Default topic is required for testing");
        }

        // Get token from credentials
        String token = null;
        if (defaultCredentialsId != null && !defaultCredentialsId.isEmpty()) {
            StringCredentials credentials = CredentialsMatchers.firstOrNull(
                    CredentialsProvider.lookupCredentials(
                            StringCredentials.class,
                            Jenkins.get(),
                            ACL.SYSTEM,
                            Collections.emptyList()
                    ),
                    CredentialsMatchers.withId(defaultCredentialsId)
            );
            if (credentials != null) {
                token = credentials.getSecret().getPlainText();
            }
        }

        if (token == null || token.isEmpty()) {
            return FormValidation.error("Valid credentials are required");
        }

        try {
            NotiferClient client = new NotiferClient(serverUrl, token);
            NotiferClient.NotiferResponse response = client.send(
                    defaultTopic,
                    "Test notification from Jenkins Notifer Plugin",
                    "Connection Test",
                    2,
                    Collections.singletonList("jenkins-test")
            );
            return FormValidation.ok("Success! Message sent with ID: " + response.getId());
        } catch (NotiferClient.NotiferException e) {
            return FormValidation.error("Failed: " + e.getMessage());
        }
    }

    /**
     * Get token from credentials ID.
     */
    public static String getTokenFromCredentials(String credentialsId, Item item) {
        if (credentialsId == null || credentialsId.isEmpty()) {
            return null;
        }

        StringCredentials credentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        StringCredentials.class,
                        item,
                        item instanceof hudson.model.Queue.Task
                                ? ((hudson.model.Queue.Task) item).getDefaultAuthentication()
                                : ACL.SYSTEM,
                        Collections.emptyList()
                ),
                CredentialsMatchers.withId(credentialsId)
        );

        return credentials != null ? credentials.getSecret().getPlainText() : null;
    }
}
