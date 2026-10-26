// Declarative pipeline for the Task Management API.
//
// Jenkins prerequisites (see README, "Jenkins"):
//   * Plugins: Pipeline, Git, JUnit, SonarQube Scanner for Jenkins
//   * Global Tool Configuration: a JDK named "JDK17" and a Maven named "Maven3"
//   * Manage Jenkins > System > SonarQube servers: a server named "SonarQube"
//     whose authentication token is stored as a "Secret text" credential
//   * SonarQube webhook -> <jenkins-url>/sonarqube-webhook/ (required by waitForQualityGate)
//
// No secret appears in this file. The Sonar token is injected by withSonarQubeEnv and is
// only referenced through shell environment variables (single-quoted sh strings).
// The pipeline uses `sh`, so it expects a Linux/macOS agent; use `bat` on a Windows agent.

pipeline {
    agent any

    options {
        timestamps()
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }

    tools {
        jdk 'JDK17'
        maven 'Maven3'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Validate Environment') {
            steps {
                sh 'java -version'
                sh 'mvn -version'
                sh 'mvn -B -ntp validate'
            }
        }

        stage('Compile') {
            steps {
                sh 'mvn -B -ntp clean compile'
            }
        }

        stage('Unit Tests') {
            steps {
                // JUnit 5 + Mockito service tests and the @WebMvcTest slice (*Test classes, Surefire)
                sh 'mvn -B -ntp test'
            }
            post {
                always {
                    junit testResults: 'target/surefire-reports/*.xml', allowEmptyResults: false
                }
            }
        }

        stage('Integration Tests') {
            steps {
                // @SpringBootTest + MockMvc + H2 (*IT classes, Failsafe). Reuses the classes built above.
                sh 'mvn -B -ntp failsafe:integration-test failsafe:verify'
            }
            post {
                always {
                    junit testResults: 'target/failsafe-reports/*.xml', allowEmptyResults: false
                }
            }
        }

        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv('SonarQube') {
                    // Reuses compiled classes and test reports from the previous stages (no `clean`).
                    // Single quotes: the shell expands the variables, Groovy never sees the token.
                    sh 'mvn -B -ntp org.sonarsource.scanner.maven:sonar-maven-plugin:sonar -Dsonar.host.url="$SONAR_HOST_URL" -Dsonar.token="$SONAR_AUTH_TOKEN"'
                }
            }
        }

        stage('Quality Gate') {
            steps {
                // Fails the build when the SonarQube quality gate is red.
                timeout(time: 10, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }
    }

    post {
        success {
            echo 'BUILD SUCCESS: compile, unit tests, integration tests, SonarQube analysis and quality gate passed.'
            echo 'Detailed code-quality findings are in the SonarQube dashboard (project key: task-management-api).'
        }
        failure {
            echo "BUILD FAILED: open the failed stage in the stage view, then check ${env.BUILD_URL}console and ${env.BUILD_URL}testReport"
        }
        unstable {
            echo "BUILD UNSTABLE: some tests reported problems. See ${env.BUILD_URL}testReport"
        }
    }
}
