

/*
 * =============================================================================
 * Evalora - Jenkins CI Pipeline
 * =============================================================================
 *
 * Stages:
 *   1. SCM CHECKOUT VALIDATION
 *   2. Frontend lint and tests
 *   3. Frontend production build
 *   4. Backend tests
 *   5. Backend package
 *   6. Docker candidate image builds
 *   7. Candidate container validation
 *   8. Image promotion
 *
 * Environment:
 *   Windows 11
 *   Java 21
 *   Node.js 22.22.2 or newer
 *   Docker Desktop
 *
 * Safety:
 *   - Uses the existing local Evalora workspace.
 *   - Does not checkout from SCM.
 *   - Uses disposable validation containers and database.
 *   - Does not touch existing Evalora database volumes.
 *   - Promotes only validated candidate images.
 *   - Does not automatically deploy or push images.
 * =============================================================================
 */

pipeline {

    agent {
        label 'built-in'
    }


    tools {
        jdk 'jdk21'
        nodejs 'node22'
    }

    options {
        timestamps()
        timeout(time: 45, unit: 'MINUTES')
        disableConcurrentBuilds()
        skipStagesAfterUnstable()
    }

    environment {
        CI = 'true'
        EVALORA_EMAIL_ENABLED = 'false'
        MAVEN_OPTS = '-Dfile.encoding=UTF-8'
    }

    stages {

        // ================================================================
        // 1. SCM CHECKOUT VALIDATION
        // ================================================================

       stage('Checkout') {
            steps {
                echo "Building from Jenkins workspace: ${pwd()}"
                echo "Git commit: ${env.GIT_COMMIT}"
            }
        }

        // ================================================================
        // 2. FRONTEND LINT AND TESTS
        // ================================================================

        stage('Frontend lint and tests') {
            steps {
                dir('frontend') {
                    script {
                        if (isUnix()) {
                            sh 'npm ci'
                            sh 'npm run lint'
                            sh 'npm test -- --no-file-parallelism'
                        } else {
                            bat 'call npm ci'
                            bat 'call npm run lint'
                            bat 'call npm test -- --no-file-parallelism'
                        }
                    }
                }
            }
        }

        // ================================================================
        // 3. FRONTEND PRODUCTION BUILD
        // ================================================================

        stage('Frontend build') {
            steps {
                dir('frontend') {
                    script {
                        if (isUnix()) {
                            sh 'npm run build'
                        } else {
                            bat 'call npm run build'
                        }
                    }
                }
            }
        }

        // ================================================================
        // 4. BACKEND TESTS
        // ================================================================

        stage('Backend tests') {
            steps {
                dir('backend') {
                    script {
                        if (isUnix()) {
                            sh 'chmod +x mvnw'
                            sh './mvnw -B test'
                        } else {
                            bat 'call mvnw.cmd -B test'
                        }
                    }
                }
            }
        }

        // ================================================================
        // 5. BACKEND PACKAGE
        // ================================================================

        stage('Backend package') {
            steps {
                dir('backend') {
                    script {
                        if (isUnix()) {
                            sh './mvnw -B -DskipTests package'
                        } else {
                            bat 'call mvnw.cmd -B -DskipTests package'
                        }
                    }
                }
            }
        }

        // ================================================================
        // 6. DOCKER CANDIDATE IMAGE BUILD
        // ================================================================

        stage('Docker image build') {
            steps {
                script {
                    def beCandidate = "evalora-backend:candidate-${env.BUILD_NUMBER}"
                    def feCandidate = "evalora-frontend:candidate-${env.BUILD_NUMBER}"

                    echo "Building candidate backend image: ${beCandidate}"

                    if (isUnix()) {
                        sh "docker build -f backend/Dockerfile -t ${beCandidate} ."
                    } else {
                        bat "docker build -f backend/Dockerfile -t ${beCandidate} ."
                    }

                    echo "Building candidate frontend image: ${feCandidate}"

                    dir('frontend') {
                        if (isUnix()) {
                            sh "docker build -t ${feCandidate} ."
                        } else {
                            bat "docker build -t ${feCandidate} ."
                        }
                    }

                    echo 'Both candidate images built successfully.'
                }
            }
        }

        // ================================================================
        // 7. CANDIDATE VALIDATION
        // ================================================================

        stage('Candidate validation') {
            steps {
                script {
                    def beCandidate = "evalora-backend:candidate-${env.BUILD_NUMBER}"
                    def feCandidate = "evalora-frontend:candidate-${env.BUILD_NUMBER}"

                    def netName = "evalora-val-net-${env.BUILD_NUMBER}"
                    def dbName  = "evalora-val-db-${env.BUILD_NUMBER}"
                    def beName  = "evalora-val-be-${env.BUILD_NUMBER}"
                    def feName  = "evalora-val-fe-${env.BUILD_NUMBER}"

                    echo "Starting candidate validation for build ${env.BUILD_NUMBER}."

                    try {

                        // 1. Create isolated Docker network

                        echo 'Creating isolated validation network...'

                        if (isUnix()) {
                            sh "docker network create ${netName}"
                        } else {
                            bat "docker network create ${netName}"
                        }

                        // 2. Start disposable PostgreSQL database

                        echo 'Starting disposable PostgreSQL 17 database...'

                        def dbCmd =
                                "docker run -d --name ${dbName} --network ${netName} " +
                                "-e POSTGRES_DB=evalora " +
                                "-e POSTGRES_USER=evalora_app " +
                                "-e POSTGRES_PASSWORD=evalora " +
                                "postgres:17-alpine"

                        if (isUnix()) {
                            sh dbCmd
                        } else {
                            bat dbCmd
                        }

                        // Wait for PostgreSQL readiness

                        echo 'Waiting for PostgreSQL readiness...'

                        def dbReady = false
                        def dbReadyCmd =
                                "docker exec ${dbName} pg_isready -U evalora_app -d evalora"

                        for (int i = 0; i < 15; i++) {
                            try {
                                if (isUnix()) {
                                    sh dbReadyCmd
                                } else {
                                    bat dbReadyCmd
                                }

                                dbReady = true
                                break

                            } catch (Exception ignored) {
                                sleep(time: 2, unit: 'SECONDS')
                            }
                        }

                        if (!dbReady) {
                            error('Candidate validation BLOCKED: PostgreSQL did not become ready.')
                        }

                        echo 'PostgreSQL validation database is ready.'

                        // 3. Start backend candidate
                        //
                        // IMPORTANT:
                        // --network-alias backend is required because
                        // frontend/nginx.conf proxies requests to "backend".

                        echo 'Starting candidate backend container...'

                        def beCmd =
                                "docker run -d --name ${beName} " +
                                "--network ${netName} " +
                                "--network-alias backend " +
                                "-e DB_URL=jdbc:postgresql://${dbName}:5432/evalora " +
                                "-e DB_USERNAME=evalora_app " +
                                "-e DB_PASSWORD=evalora " +
                                "-e JWT_SECRET=candidate-validation-jwt-secret-minimum-32-bytes-long " +
                                "-e EVALORA_OTP_PEPPER=candidate-validation-otp-pepper-at-least-32-chars " +
                                "-e EVALORA_FRONTEND_URL=http://localhost:5173 " +
                                "-e EVALORA_CORS_ALLOWED_ORIGINS=http://localhost:5173 " +
                                "-e SPRING_PROFILES_ACTIVE=prod " +
                                "${beCandidate}"

                        if (isUnix()) {
                            sh beCmd
                        } else {
                            bat beCmd
                        }

                        // 4. Start frontend candidate

                        echo 'Starting candidate frontend container...'

                        def feCmd =
                                "docker run -d --name ${feName} " +
                                "--network ${netName} ${feCandidate}"

                        if (isUnix()) {
                            sh feCmd
                        } else {
                            bat feCmd
                        }

                        // 5. Frontend health check

                        echo 'Checking candidate frontend health...'

                        def feHealthy = false

                        def feProbeCmd =
                                "docker exec ${feName} wget -q -O /dev/null http://127.0.0.1:80/"

                        for (int i = 0; i < 10; i++) {
                            try {
                                if (isUnix()) {
                                    sh feProbeCmd
                                } else {
                                    bat feProbeCmd
                                }

                                feHealthy = true
                                break

                            } catch (Exception ignored) {
                                sleep(time: 2, unit: 'SECONDS')
                            }
                        }

                        if (!feHealthy) {
                            error('Candidate validation FAILED: frontend did not become healthy.')
                        }

                        echo 'Frontend candidate validation: HEALTHY'

                        // 6. Backend health check

                        echo 'Checking candidate backend /actuator/health...'

                        def beHealthy = false

                        def beProbeCmd =
                                "docker exec ${beName} curl -fsS http://127.0.0.1:8080/actuator/health"

                        for (int i = 0; i < 20; i++) {
                            try {
                                if (isUnix()) {
                                    sh beProbeCmd
                                } else {
                                    bat beProbeCmd
                                }

                                beHealthy = true
                                break

                            } catch (Exception ignored) {
                                sleep(time: 3, unit: 'SECONDS')
                            }
                        }

                        if (!beHealthy) {
                            error('Candidate validation FAILED: backend did not become healthy.')
                        }

                        echo 'Backend candidate validation: HEALTHY'

                        echo 'ALL CANDIDATE VALIDATION CHECKS PASSED.'

                    } finally {

                        // 7. Clean only temporary validation resources

                        echo 'Cleaning up candidate validation resources...'

                        def cleanupCmd =
                                "docker rm -f ${beName} ${feName} ${dbName}"

                        def netCleanupCmd =
                                "docker network rm ${netName}"

                        if (isUnix()) {
                            sh "${cleanupCmd} || true"
                            sh "${netCleanupCmd} || true"
                        } else {
                            bat "cmd /c \"${cleanupCmd}\" || exit 0"
                            bat "cmd /c \"${netCleanupCmd}\" || exit 0"
                        }

                        echo 'Candidate validation cleanup completed.'
                    }
                }
            }
        }

        // ================================================================
        // 8. IMAGE PROMOTION
        // ================================================================

        stage('Image promotion') {
            steps {
                script {
                    def beCandidate = "evalora-backend:candidate-${env.BUILD_NUMBER}"
                    def feCandidate = "evalora-frontend:candidate-${env.BUILD_NUMBER}"

                    def bePromoted = "evalora-backend:${env.BUILD_NUMBER}"
                    def fePromoted = "evalora-frontend:${env.BUILD_NUMBER}"

                    echo 'Promoting validated candidate images...'

                    if (isUnix()) {
                        sh "docker tag ${beCandidate} ${bePromoted}"
                        sh "docker tag ${feCandidate} ${fePromoted}"
                    } else {
                        bat "docker tag ${beCandidate} ${bePromoted}"
                        bat "docker tag ${feCandidate} ${fePromoted}"
                    }

                    // Verify image ID parity.
                    // Promotion must not rebuild either image.

                    def beCandId = ''
                    def bePromId = ''
                    def feCandId = ''
                    def fePromId = ''

                    if (isUnix()) {

                        beCandId = sh(
                                script: "docker inspect --format='{{.Id}}' ${beCandidate}",
                                returnStdout: true
                        ).trim()

                        bePromId = sh(
                                script: "docker inspect --format='{{.Id}}' ${bePromoted}",
                                returnStdout: true
                        ).trim()

                        feCandId = sh(
                                script: "docker inspect --format='{{.Id}}' ${feCandidate}",
                                returnStdout: true
                        ).trim()

                        fePromId = sh(
                                script: "docker inspect --format='{{.Id}}' ${fePromoted}",
                                returnStdout: true
                        ).trim()

                    } else {

                        beCandId = bat(
                                script: "@docker inspect --format=\"{{.Id}}\" ${beCandidate}",
                                returnStdout: true
                        ).trim()

                        bePromId = bat(
                                script: "@docker inspect --format=\"{{.Id}}\" ${bePromoted}",
                                returnStdout: true
                        ).trim()

                        feCandId = bat(
                                script: "@docker inspect --format=\"{{.Id}}\" ${feCandidate}",
                                returnStdout: true
                        ).trim()

                        fePromId = bat(
                                script: "@docker inspect --format=\"{{.Id}}\" ${fePromoted}",
                                returnStdout: true
                        ).trim()
                    }

                    echo "Backend Candidate ID: ${beCandId}"
                    echo "Backend Promoted ID:  ${bePromId}"

                    echo "Frontend Candidate ID: ${feCandId}"
                    echo "Frontend Promoted ID:  ${fePromId}"

                    if (!beCandId || beCandId != bePromId) {
                        error('Backend image promotion verification FAILED.')
                    }

                    if (!feCandId || feCandId != fePromId) {
                        error('Frontend image promotion verification FAILED.')
                    }

                    echo 'Backend image promotion: VERIFIED'
                    echo 'Frontend image promotion: VERIFIED'

                    echo 'Image promotion completed successfully.'
                    echo 'No image was rebuilt during promotion.'
                    echo 'No production deployment was performed.'
                }
            }
        }
    }

    // ================================================================
    // POST-BUILD ACTIONS
    // ================================================================


        // ================================================================
    // POST-BUILD ACTIONS
    // ================================================================

    post {
        always {
            junit(
                allowEmptyResults: true,
                testResults: 'backend/target/surefire-reports/*.xml'
            )

            archiveArtifacts(
                allowEmptyArchive: true,
                artifacts: 'frontend/dist/**,backend/target/*.jar',
                fingerprint: true
            )
        }

        success {
            echo 'EVALORA CI PIPELINE PASSED.'
        }

        failure {
            echo 'PIPELINE FAILED.'
            echo 'Check the first failing stage in the Jenkins console output.'
            echo 'Images were not automatically deployed.'
        }
    }
}
