# Notifer Jenkins Plugin

Send notifications to [Notifer](https://notifer.io) topics from Jenkins pipelines and freestyle jobs.

## Features

- **Pipeline Step**: Use `notifer()` in declarative and scripted pipelines
- **Post-Build Action**: Add notifications to freestyle jobs
- **Flexible Configuration**: Global defaults with per-job overrides
- **Environment Variables**: Full support for variable expansion
- **Conditional Notifications**: Send based on build result (success, failure, unstable, aborted)
- **Priority Mapping**: Auto-detect priority based on build result or set manually (1-5)
- **Credentials Integration**: Secure token storage using Jenkins Credentials

## Installation

### From Jenkins Update Center (Recommended)

1. Go to **Manage Jenkins** > **Plugins** > **Available plugins**
2. Search for "Notifer"
3. Install and restart Jenkins

### Manual Installation

1. Download the latest `.hpi` file from [Releases](https://github.com/jenkinsci/notifer-plugin/releases)
2. Go to **Manage Jenkins** > **Plugins** > **Advanced settings**
3. Upload the `.hpi` file
4. Restart Jenkins

## Configuration

### 1. Create Credentials

1. Go to **Manage Jenkins** > **Credentials**
2. Add a new **Secret text** credential
3. Enter your Notifer topic access token (starts with `tk_`)
4. Give it an ID like `notifer-token`

### 2. Global Configuration

1. Go to **Manage Jenkins** > **System**
2. Find the **Notifer** section
3. Configure:
   - **Server URL**: `https://app.notifer.io` (or your self-hosted URL)
   - **Default Credentials**: Select the credentials you created
   - **Default Topic**: Your default topic name
   - **Default Priority**: 1-5 (3 is default)
4. Click **Test Connection** to verify

## Usage

### Pipeline (Declarative)

```groovy
pipeline {
    agent any

    stages {
        stage('Build') {
            steps {
                sh 'make build'
            }
        }
    }

    post {
        success {
            notifer(
                message: "Build #${BUILD_NUMBER} succeeded",
                title: "Build Success",
                priority: 2,
                tags: ['success', 'jenkins']
            )
        }
        failure {
            notifer(
                message: "Build #${BUILD_NUMBER} FAILED!",
                title: "Build Failed",
                priority: 5,
                tags: ['failure', 'jenkins', 'urgent']
            )
        }
    }
}
```

### Pipeline (Scripted)

```groovy
node {
    try {
        stage('Build') {
            sh 'make build'
        }

        notifer(
            topic: 'ci-notifications',
            message: "Build successful: ${env.JOB_NAME} #${env.BUILD_NUMBER}",
            priority: 2
        )
    } catch (Exception e) {
        notifer(
            topic: 'ci-notifications',
            message: "Build failed: ${env.JOB_NAME} #${env.BUILD_NUMBER}\nError: ${e.message}",
            priority: 5,
            failOnError: false
        )
        throw e
    }
}
```

### Freestyle Job

1. Open your job configuration
2. Add **Post-build Action** > **Send Notifer Notification**
3. Configure:
   - Topic (optional - uses global default)
   - Message (optional - auto-generated if empty)
   - Priority
   - When to notify (success, failure, unstable, aborted)

## Step Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `message` | String | Yes | - | Notification message. Supports environment variables. |
| `topic` | String | No | Global default | Topic name to send to |
| `title` | String | No | - | Optional message title |
| `priority` | int | No | 3 | Priority level 1-5 |
| `tags` | List<String> | No | [] | List of tags (max 5) |
| `credentialsId` | String | No | Global default | Credentials ID for token |
| `serverUrl` | String | No | Global default | Notifer server URL |
| `failOnError` | boolean | No | false | Fail build if notification fails |

## Environment Variables

The following variables are available in messages:

- `${BUILD_NUMBER}` - Build number
- `${JOB_NAME}` - Job name
- `${BUILD_URL}` - Full URL to build
- `${BUILD_STATUS}` - Build result
- `${GIT_COMMIT}` - Git commit hash (if using Git)
- `${GIT_BRANCH}` - Git branch name (if using Git)

## Examples

### Notify with Custom Topic per Branch

```groovy
notifer(
    topic: "builds-${env.BRANCH_NAME}",
    message: "Deployed to ${env.BRANCH_NAME}",
    priority: env.BRANCH_NAME == 'main' ? 4 : 2
)
```

### Notify on Deployment

```groovy
stage('Deploy') {
    steps {
        sh './deploy.sh'
    }
    post {
        success {
            notifer(
                message: "Deployed ${env.VERSION} to production",
                title: "Deployment Complete",
                priority: 4,
                tags: ['deploy', 'production', env.VERSION]
            )
        }
    }
}
```

### Send Build Report

```groovy
script {
    def testResults = junit 'target/test-results/*.xml'
    notifer(
        message: """Build Report:
- Tests: ${testResults.totalCount}
- Passed: ${testResults.passCount}
- Failed: ${testResults.failCount}
- Duration: ${currentBuild.durationString}""",
        title: "Build #${BUILD_NUMBER} Report",
        priority: testResults.failCount > 0 ? 4 : 2
    )
}
```

## Troubleshooting

### "Credentials are required"

Make sure you have:
1. Created a "Secret text" credential with your Notifer token
2. Selected the credential in global configuration or the job

### "Topic is required"

Set either:
- Global default topic in **Manage Jenkins** > **System** > **Notifer**
- Topic parameter in the `notifer()` step

### "Failed to send notification"

Check:
1. Token has **write** or **read_write** access
2. Topic exists and is private
3. Server URL is correct
4. Network connectivity to Notifer server

## Development

### Build

```bash
mvn clean package
```

### Run locally

```bash
mvn hpi:run
```

### Run tests

```bash
mvn test
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run tests: `mvn test`
5. Submit a pull request

## License

MIT License - see [LICENSE](LICENSE)

## Links

- [Notifer](https://notifer.io) - Push notification service
- [Notifer Documentation](https://docs.notifer.io)
- [Jenkins Plugin Site](https://plugins.jenkins.io/notifer)
- [Issue Tracker](https://github.com/jenkinsci/notifer-plugin/issues)
