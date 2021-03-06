def call(body) {

  def cfg = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = cfg
  body()

  def agent = (cfg.agent != null) ? cfg.agent : ''

  node(agent) {
    // set current step for the notification handler
    def pipelineStep = "start"
    def repositoryUrl = scm.getUserRemoteConfigs()[0].getUrl()
    def config = [:]

    try {
      // https://jenkins.io/doc/pipeline/steps/workflow-scm-step/
      stage('Checkout') {
        pipelineStep = "checkout"

        sh(script: 'git config --global user.email "you@example.com"')
        sh(script: 'git config --global user.name "Your Name"')

        if (!fileExists('repo')){ sh(script: "mkdir -p repo") }
        dir('repo') { checkout scm }

        // create a changelog
        def changelog = getChangelog()
        echo(changelog)
        writeFile(file: "./changelog.txt", text: changelog)

        // process config
        config = processNodeConfig(cfg, env.BRANCH_NAME, env.BUILD_NUMBER, repositoryUrl)
        config.envDetails.logBucketPath = config.envDetails.logBucketPath ?: "build"
      }

      properties([
        disableConcurrentBuilds(),
        buildDiscarder(logRotator(numToKeepStr: config.buildsToKeep.toString()))
      ])

      // start of Build stage
      stage('Build') {
        pipelineStep = "build"
        createNodeComposeBuildEnv(config, './build.json') // create docker-compose file
        sh(script: "docker-compose -f ./build.json build")
      }
      // end of Build stage

      // start of Docker push image stage
      stage('Push image') {
        pipelineStep = "push image"
        sh(script: "gcloud auth configure-docker --configuration ${config.envDetails.gcpProjectId}")
        sh(script: "docker push ${config.dockerImageName}:${config.dockerImageTag}")
      }
      // end of Docker push image stage

      // start of Test stage
      stage('Test') {
        pipelineStep = "test"
        if (config.envDetails.runTests) {
          createNodeComposeTestEnv(config, './test.json')
          sh(script: "docker-compose -f test.json up --no-start")

          try {
            sh(script: "docker-compose -f test.json run main npm run ci-test")
          } finally {
            // record tests and coverage results
            recordNodeTestResults(true, 10.0)
            recordNodeCoverageResults()
          }
        } else {
          echo "Tests have been skipped based on the Jenkinsfile configuration"
        }
      }
      // end of Test stage

      // start of Lint stage
      stage('Lint') {
        pipelineStep = "lint"
        if(config.envDetails.runLint) {
          createNodeComposeLintEnv(config, './lint.json')
          sh(script: "docker-compose -f lint.json up --no-start")
          sh(script: "docker-compose -f lint.json run main npm run ci-lint")

          // set correct path to tested files in the lint results
          sh(script: "sed -i 's#/usr/src/app/#${config.workspace}/repo/#g' ci-outputs/lint/checkstyle-result.xml")
          // record lint results
          // checkstyle plugin is dead and this pipeline is deprecated
          //recordNodeLintResults()
        } else {
          echo "Lint stage has been skipped based on the Jenkinsfile configuration"
        }
      }
      // end of Lint stage

      // start of NPM Audit stage
      stage('NPM Audit') {
        pipelineStep = "npm-audit"
        if(config.envDetails.runNpmAudit) {
          createNodeComposeNpmAuditEnv(config, './npm-audit.json')
          sh(script: "docker-compose -f npm-audit.json up --no-start")
          sh(script: "docker-compose -f npm-audit.json run main npm audit --production --audit-level=high")
        } else {
          echo "NPM Audit stage has been skipped based on the Jenkinsfile configuration"
        }
      }
      // end of NPM Audit stage

      // start of Documentation stage
      stage('Documentation') {
        if(config.documentation) {
          createNodeComposeDocsEnv(config, './documentation.json')
          sh(script: "docker-compose -f documentation.json up --no-start")
          sh(script: "docker-compose -f documentation.json run main npm run docs")
          createNodeDocumentationGcsBucket(config)
          uploadNodeDocumentation(config)
        } else {
          echo("skipping documentation.")
        }
      }
      // end of Documentation stage

      // start of Deploy stage
      stage('Deploy') {
        pipelineStep = "deploy"

        // if specified, obtain secrets
        createNodeSecretsManifest(config)

        // create string with all --set flags so we cab reuse them if needed
        def setParams = "--set general.imageName=${config.dockerImageName} " +
          "--set general.imageTag=${config.dockerImageTag} " +
          "--set general.appName=${config.appName} " +
          "--set general.projectName=${config.projectFriendlyName} " +
          "--set general.environment=${config.envDetails.friendlyEnvName} " +
          "--set general.meta.buildHash=${config.commitHash} " +
          "--set general.meta.branch=${config.branch} " +
          "--set general.meta.repositoryUrl=${config.repositoryUrl} " +
          "--set general.meta.slackChannel=${config.slackChannel} " +
          "--set general.gcpProjectId=${config.envDetails.gcpProjectId} "

        def dryRun = config.envDetails.dryRun ?: false // prepare string for --dry-run flags
        helmMode = config.envDetails.helmMode ?: "native"

        // sync repos
        sh(script: "helm repo update")

        if(helmMode == "native") {

          // create version flag
          chartVersionFlag = config.envDetails.chartVersion ? "--version ${config.envDetails.chartVersion} " : ""
          // create force flag
          releaseForceUpgradeFlag = config.envDetails.releaseForceUpgrade ? "--force " : ""

          // upgrade or install release
          def deployCommand = "helm upgrade " +
            "--install " +
            "--kubeconfig ${config.kubeConfigPath} " +
            "-f ${config.workspace}/${config.envDetails.helmValues} " +
            "-f ${config.workspace}/secrets-deployment.json " +
            setParams +
            chartVersionFlag +
            releaseForceUpgradeFlag +
            "--dry-run=${dryRun.toString()} " +
            "--namespace ${config.envDetails.k8sNamespace} " +
            "${config.helmReleaseName} " +
            "${config.envDetails.helmChart}"

          // run the final deploy script
          sh(script: deployCommand)

        } else if(helmMode == "template") {

          // create version flag
          chartVersionFlag = config.envDetails.chartVersion ? "--version ${config.envDetails.chartVersion} " : ""

          // create long Yaml with all Kubernetes resources
          def templateCommand = "helm template " +
            "-f ${config.workspace}/${config.envDetails.helmValues} " +
            "-f ${config.workspace}/secrets-deployment.json " +
            setParams +
            "-n ${config.helmReleaseName} " +
            "${config.envDetails.helmChart} " +
            "> ./helm-template.yaml"
          sh(script: templateCommand)

          // apply Kubernetes manifest
          def deployCommand = "kubectl " +
            "--kubeconfig ${config.kubeConfigPath} " +
            "apply " +
            "-f ./helm-template.yaml " +
            "-n ${config.envDetails.k8sNamespace} " +
            "--dry-run=${dryRun}"

          // execute kubectl apply
          sh(script: deployCommand)

        } else {
          error("unknown helmMode '${helmMode}'")
        }

        // get status of the services within the namespace
        if(!config.envDetails.dryRun) {
        }
      }
      // end of Deploy stage

      // start of tag stage
      stage('Tag') {
        pipelineStep = "deploy"
        if(config.gitlabTagCredentials) {
          createNodeGitlabTag(config)
        } else {
          echo("Skipping Gitlab tagging")
        }
      }
      // end of tag stage

    } catch(err) {
      currentBuild.result = "FAILURE"
      println(err.toString());
      println(err.getMessage());
      println(err.getStackTrace());
      throw err
    } finally {
      // remove build containers
      if(fileExists('build.json')) {
        sh(script: 'docker-compose -f build.json rm -s -f')
      }

      // remove documentation containers
      if(config.documentation) {
        if(fileExists('documentation.json')) {
          sh(script: 'docker-compose -f documentation.json rm -s -f')
        }
      }

      // remove test containers
      if(config.testConfig) {
        if(fileExists('test.json')) {
          sh(script: 'docker-compose -f test.json rm -s -f')
        }
      }

      // remove lint containers
      if(config.runLint) {
        if(fileExists('lint.json')) {
          sh(script: 'docker-compose -f lint.json rm -s -f')
        }
      }

      // remove npm-audit containers
      if(config.runNpmAudit) {
        if(fileExists('npm-audit.json')) {
          sh(script: 'docker-compose -f npm-audit.json rm -s -f')
        }
      }

      // sometimes you need to check these files you know
      if(!config.envDetails.debugMode) {
        sh(script: 'rm -rf ./test.json')
        sh(script: 'rm -rf ./build.json')
        sh(script: 'rm -rf ./secrets')
        sh(script: 'rm -rf ./values.json')
        sh(script: 'rm -rf ./secrets-deployment.json')
        sh(script: 'rm -rf ./secrets-test.json')
        sh(script: 'rm -rf ./test-tmp.json')
        sh(script: 'rm -rf ./helm-template.yaml')
      } else {
        echo("DEBUG MODE: on")
      }

      // send slack notification
      if(config.slackChannel) {
        notifyNodeBuild(
          buildStatus: currentBuild.result,
          buildType: 'Build',
          channel: config.slackChannel,
          reason: pipelineStep
        )
      }

      if(config.envDetails.logToBucket) {
        withCredentials([usernamePassword(credentialsId: config.envDetails.jenkinsCredentialsId, usernameVariable: 'username', passwordVariable: 'password')]) {
          sh(script: "curl -u ${username}:${password} -L ${env.BUILD_URL}/consoleText -o ${config.workspace}/consoleText.log")
          sh(script: "gsutil cp ${config.workspace}/consoleText.log ${config.envDetails.logBucketUrl}/${config.envDetails.logBucketPath}/consoleText.${env.BUILD_NUMBER}.log")
        }
      }
    }
  }
}
