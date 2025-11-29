package io.notifer.jenkins;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.*;
import hudson.model.*;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Post-build action for sending notifications to Notifer.
 * Used in freestyle jobs and as post-build step in pipelines.
 */
public class NotiferNotifier extends Notifier implements SimpleBuildStep {

    private String topic;
    private String message;
    private String title;
    private int priority = 3;
    private String tags;
    private String credentialsId;
    private boolean notifySuccess = true;
    private boolean notifyFailure = true;
    private boolean notifyUnstable = true;
    private boolean notifyAborted = false;

    @DataBoundConstructor
    public NotiferNotifier() {
    }

    // --- Getters ---

    public String getTopic() {
        return topic;
    }

    public String getMessage() {
        return message;
    }

    public String getTitle() {
        return title;
    }

    public int getPriority() {
        return priority;
    }

    public String getTags() {
        return tags;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public boolean isNotifySuccess() {
        return notifySuccess;
    }

    public boolean isNotifyFailure() {
        return notifyFailure;
    }

    public boolean isNotifyUnstable() {
        return notifyUnstable;
    }

    public boolean isNotifyAborted() {
        return notifyAborted;
    }

    // --- Setters ---

    @DataBoundSetter
    public void setTopic(String topic) {
        this.topic = topic;
    }

    @DataBoundSetter
    public void setMessage(String message) {
        this.message = message;
    }

    @DataBoundSetter
    public void setTitle(String title) {
        this.title = title;
    }

    @DataBoundSetter
    public void setPriority(int priority) {
        this.priority = Math.max(1, Math.min(5, priority));
    }

    @DataBoundSetter
    public void setTags(String tags) {
        this.tags = tags;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    @DataBoundSetter
    public void setNotifySuccess(boolean notifySuccess) {
        this.notifySuccess = notifySuccess;
    }

    @DataBoundSetter
    public void setNotifyFailure(boolean notifyFailure) {
        this.notifyFailure = notifyFailure;
    }

    @DataBoundSetter
    public void setNotifyUnstable(boolean notifyUnstable) {
        this.notifyUnstable = notifyUnstable;
    }

    @DataBoundSetter
    public void setNotifyAborted(boolean notifyAborted) {
        this.notifyAborted = notifyAborted;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull FilePath workspace,
                        @NonNull EnvVars envVars, @NonNull Launcher launcher,
                        @NonNull TaskListener listener) throws InterruptedException, IOException {

        PrintStream logger = listener.getLogger();
        Result result = run.getResult();

        // Check if we should notify for this result
        if (!shouldNotify(result)) {
            logger.println("[Notifer] Skipping notification for result: " + result);
            return;
        }

        NotiferGlobalConfiguration globalConfig = NotiferGlobalConfiguration.get();

        // Resolve values (step > global)
        String serverUrl = globalConfig.getServerUrl();
        String resolvedTopic = topic != null && !topic.isEmpty() ? topic : globalConfig.getDefaultTopic();
        String resolvedCredentialsId = credentialsId != null && !credentialsId.isEmpty()
                ? credentialsId
                : globalConfig.getDefaultCredentialsId();

        if (resolvedTopic == null || resolvedTopic.isEmpty()) {
            logger.println("[Notifer] ERROR: Topic is required");
            return;
        }

        if (resolvedCredentialsId == null || resolvedCredentialsId.isEmpty()) {
            logger.println("[Notifer] ERROR: Credentials are required");
            return;
        }

        // Get token
        String token = NotiferGlobalConfiguration.getTokenFromCredentials(resolvedCredentialsId, run.getParent());
        if (token == null || token.isEmpty()) {
            logger.println("[Notifer] ERROR: Could not retrieve token from credentials");
            return;
        }

        // Build default message if not provided
        String resolvedMessage = message;
        if (resolvedMessage == null || resolvedMessage.isEmpty()) {
            resolvedMessage = buildDefaultMessage(run, result);
        }

        // Build default title if not provided
        String resolvedTitle = title;
        if (resolvedTitle == null || resolvedTitle.isEmpty()) {
            resolvedTitle = buildDefaultTitle(run, result);
        }

        // Expand environment variables
        resolvedTopic = envVars.expand(resolvedTopic);
        resolvedMessage = envVars.expand(resolvedMessage);
        resolvedTitle = envVars.expand(resolvedTitle);

        // Determine priority based on result if using default
        int resolvedPriority = priority > 0 ? priority : getPriorityForResult(result);

        // Parse tags
        List<String> tagList = parseTags(tags, result, envVars);

        logger.println("[Notifer] Sending notification to topic: " + resolvedTopic);

        try {
            NotiferClient client = new NotiferClient(serverUrl, token);
            NotiferClient.NotiferResponse response = client.send(
                    resolvedTopic, resolvedMessage, resolvedTitle, resolvedPriority, tagList
            );
            logger.println("[Notifer] Notification sent successfully. ID: " + response.getId());

        } catch (NotiferClient.NotiferException e) {
            logger.println("[Notifer] Failed to send notification: " + e.getMessage());
        }
    }

    private boolean shouldNotify(Result result) {
        if (result == null) {
            return notifySuccess; // Still running, treat as success
        }
        if (result == Result.SUCCESS) {
            return notifySuccess;
        }
        if (result == Result.FAILURE) {
            return notifyFailure;
        }
        if (result == Result.UNSTABLE) {
            return notifyUnstable;
        }
        if (result == Result.ABORTED) {
            return notifyAborted;
        }
        return true;
    }

    private String buildDefaultMessage(Run<?, ?> run, Result result) {
        String jobName = run.getParent().getFullDisplayName();
        int buildNumber = run.getNumber();
        String status = result != null ? result.toString() : "RUNNING";
        String url = Jenkins.get().getRootUrl() + run.getUrl();

        return String.format("Build #%d %s\nJob: %s\nDetails: %s",
                buildNumber, status, jobName, url);
    }

    private String buildDefaultTitle(Run<?, ?> run, Result result) {
        String jobName = run.getParent().getDisplayName();
        String status = result != null ? result.toString() : "RUNNING";
        return String.format("[%s] %s #%d", status, jobName, run.getNumber());
    }

    private int getPriorityForResult(Result result) {
        if (result == null || result == Result.SUCCESS) {
            return 2; // Low priority for success
        }
        if (result == Result.UNSTABLE) {
            return 3; // Medium for unstable
        }
        if (result == Result.FAILURE) {
            return 5; // High for failure
        }
        return 3;
    }

    private List<String> parseTags(String tagsString, Result result, EnvVars envVars) {
        List<String> tagList = new ArrayList<>();

        // Add result as tag
        if (result != null) {
            tagList.add(result.toString().toLowerCase());
        }

        // Add jenkins tag
        tagList.add("jenkins");

        // Parse custom tags
        if (tagsString != null && !tagsString.isEmpty()) {
            String expanded = envVars.expand(tagsString);
            String[] parts = expanded.split("[,\\s]+");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && tagList.size() < 5) {
                    tagList.add(trimmed);
                }
            }
        }

        // Limit to 5 tags
        if (tagList.size() > 5) {
            tagList = tagList.subList(0, 5);
        }

        return tagList;
    }

    /**
     * Descriptor for the notifier.
     */
    @Extension
    @Symbol("notiferNotify")
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Send Notifer Notification";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        // --- Form Validation ---

        public FormValidation doCheckTopic(@QueryParameter String value) {
            // Optional - can use global default
            return FormValidation.ok();
        }

        public FormValidation doCheckPriority(@QueryParameter int value) {
            if (value < 0 || value > 5) {
                return FormValidation.warning("Priority should be between 1 and 5, or 0 for auto-detect");
            }
            return FormValidation.ok();
        }

        /**
         * Fill credentials dropdown.
         */
        public ListBoxModel doFillCredentialsIdItems(
                @AncestorInPath Item item,
                @QueryParameter String credentialsId) {

            StandardListBoxModel result = new StandardListBoxModel();
            NotiferGlobalConfiguration globalConfig = NotiferGlobalConfiguration.get();

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
                            URIRequirementBuilder.fromUri(globalConfig.getServerUrl()).build(),
                            CredentialsMatchers.always()
                    )
                    .includeCurrentValue(credentialsId);
        }
    }
}
