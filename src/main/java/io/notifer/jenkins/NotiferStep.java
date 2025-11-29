package io.notifer.jenkins;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.workflow.steps.*;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.*;

/**
 * Pipeline step for sending notifications to Notifer.
 *
 * Usage in Jenkinsfile:
 * <pre>
 * notifer(
 *     topic: 'ci-notifications',
 *     message: 'Build completed',
 *     title: 'Jenkins Build',
 *     priority: 3,
 *     tags: ['jenkins', 'build']
 * )
 * </pre>
 */
public class NotiferStep extends Step implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String message;
    private String topic;
    private String title;
    private int priority = 3;
    private List<String> tags;
    private String credentialsId;
    private String serverUrl;
    private boolean failOnError = false;

    /**
     * Constructor with required message parameter.
     */
    @DataBoundConstructor
    public NotiferStep(@NonNull String message) {
        this.message = message;
    }

    // --- Getters ---

    @NonNull
    public String getMessage() {
        return message;
    }

    public String getTopic() {
        return topic;
    }

    public String getTitle() {
        return title;
    }

    public int getPriority() {
        return priority;
    }

    public List<String> getTags() {
        return tags;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public boolean isFailOnError() {
        return failOnError;
    }

    // --- Setters ---

    @DataBoundSetter
    public void setTopic(String topic) {
        this.topic = topic;
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
    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }

    @DataBoundSetter
    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    @DataBoundSetter
    public void setFailOnError(boolean failOnError) {
        this.failOnError = failOnError;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new NotiferStepExecution(this, context);
    }

    /**
     * Step execution implementation.
     */
    private static class NotiferStepExecution extends SynchronousNonBlockingStepExecution<NotiferClient.NotiferResponse> {
        private static final long serialVersionUID = 1L;

        private final transient NotiferStep step;

        NotiferStepExecution(NotiferStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        @Override
        protected NotiferClient.NotiferResponse run() throws Exception {
            TaskListener listener = getContext().get(TaskListener.class);
            Run<?, ?> run = getContext().get(Run.class);
            EnvVars envVars = getContext().get(EnvVars.class);
            PrintStream logger = listener.getLogger();

            NotiferGlobalConfiguration globalConfig = NotiferGlobalConfiguration.get();

            // Resolve server URL (step > global)
            String serverUrl = step.serverUrl != null && !step.serverUrl.isEmpty()
                    ? step.serverUrl
                    : globalConfig.getServerUrl();

            // Resolve topic (step > global)
            String topic = step.topic != null && !step.topic.isEmpty()
                    ? step.topic
                    : globalConfig.getDefaultTopic();

            if (topic == null || topic.isEmpty()) {
                throw new IllegalArgumentException("Topic is required. Set it in the step or global configuration.");
            }

            // Resolve credentials (step > global)
            String credentialsId = step.credentialsId != null && !step.credentialsId.isEmpty()
                    ? step.credentialsId
                    : globalConfig.getDefaultCredentialsId();

            if (credentialsId == null || credentialsId.isEmpty()) {
                throw new IllegalArgumentException("Credentials are required. Set credentialsId in the step or global configuration.");
            }

            // Get token from credentials
            String token = NotiferGlobalConfiguration.getTokenFromCredentials(credentialsId, run.getParent());
            if (token == null || token.isEmpty()) {
                throw new IllegalArgumentException("Could not retrieve token from credentials: " + credentialsId);
            }

            // Expand environment variables in message and title
            String message = envVars.expand(step.message);
            String title = step.title != null ? envVars.expand(step.title) : null;
            topic = envVars.expand(topic);

            // Resolve priority (step > global default)
            int priority = step.priority > 0 ? step.priority : globalConfig.getDefaultPriority();

            // Expand tags
            List<String> tags = step.tags;
            if (tags != null) {
                List<String> expandedTags = new ArrayList<>();
                for (String tag : tags) {
                    expandedTags.add(envVars.expand(tag));
                }
                tags = expandedTags;
            }

            logger.println("[Notifer] Sending notification to topic: " + topic);

            try {
                NotiferClient client = new NotiferClient(serverUrl, token);
                NotiferClient.NotiferResponse response = client.send(topic, message, title, priority, tags);

                logger.println("[Notifer] Notification sent successfully. ID: " + response.getId());
                return response;

            } catch (NotiferClient.NotiferException e) {
                String errorMessage = "[Notifer] Failed to send notification: " + e.getMessage();

                if (step.failOnError) {
                    throw new RuntimeException(errorMessage, e);
                } else {
                    logger.println(errorMessage);
                    return null;
                }
            }
        }
    }

    /**
     * Descriptor for the step.
     */
    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return new HashSet<>(Arrays.asList(
                    Run.class,
                    TaskListener.class,
                    EnvVars.class
            ));
        }

        @Override
        public String getFunctionName() {
            return "notifer";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Send Notifer Notification";
        }

        // --- Form Validation ---

        public FormValidation doCheckMessage(@QueryParameter String value) {
            if (value == null || value.isEmpty()) {
                return FormValidation.error("Message is required");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckTopic(@QueryParameter String value) {
            // Topic is optional at step level (can use global default)
            return FormValidation.ok();
        }

        public FormValidation doCheckPriority(@QueryParameter int value) {
            if (value < 1 || value > 5) {
                return FormValidation.warning("Priority should be between 1 and 5. Using default.");
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
