#!groovy
// Copyright (c) IBM 2017

/*------------------------
  Typical usage:
  @Library('MicroserviceBuilder') _
  microserviceBuilderPipeline {
    image = 'microservice-test'
  }

  The following parameters may also be specified. Their defaults are shown below.
  These are the names of images to be downloaded from https://hub.docker.com/.

    mavenImage = 'maven:3.5.0-jdk-8'
    dockerImage = 'docker'
    kubectlImage = 'lachlanevenson/k8s-kubectl:v1.6.0'
    mvnCommands = 'clean package'

  You can also specify:

    build = 'true' - any value other than 'true' == false
    deploy = 'true' - any value other than 'true' == false
    deployBranch = 'master' - only builds from this branch are deployed
    namespace = 'targetNamespace' - deploys into Kubernetes targetNamespace.
      Default is to deploy into Jenkins' namespace.

-------------------------*/

def call(body) {
  def config = [:]
  // Parameter expansion works after the call to body() below.
  // See https://jenkins.io/doc/book/pipeline/shared-libraries/ 'Defining a more structured DSL'
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = config
  body()

  print "microserviceBuilderPipeline: config = ${config}"

  def image = config.image
  def maven = (config.mavenImage == null) ? 'maven:3.5.0-jdk-8' : config.mavenImage
  def docker = (config.dockerImage == null) ? 'docker' : config.dockerImage
  def kubectl = (config.kubectlImage == null) ? 'lachlanevenson/k8s-kubectl:v1.6.0' : config.kubectlImage
  def mvnCommands = (config.mvnCommands == null) ? 'clean package' : config.mvnCommands
  def registry = System.getenv("REGISTRY").trim()
  def registrySecret = System.getenv("REGISTRY_SECRET").trim()
  def build = (config.build ?: System.getenv ("BUILD")).trim().toLowerCase() == 'true'
  def deploy = (config.deploy ?: System.getenv ("DEPLOY")).trim().toLowerCase() == 'true'
  def namespace = config.namespace ?: (System.getenv("NAMESPACE") ?: "").trim()

  // Extra elvis here to cope with env var being absent until pipeline chart catches up
  def deployBranch = config.deployBranch ?: ((System.getenv("DEFAULT_DEPLOY_BRANCH") ?: "").trim() ?: 'master')

  print "microserviceBuilderPipeline: registry=${registry} registrySecret=${registrySecret} build=${build} deploy=${deploy} deployBranch=${deployBranch} namespace=${namespace}"

  /* Only mount registry secret if it's present */
  def volumes = [ hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock') ]
  if (registrySecret) {
    volumes += secretVolume(secretName: registrySecret, mountPath: '/root')
  }
  print "microserviceBuilderPipeline: volumes = ${volumes}"

  testNamespace = "testns-${env.BUILD_ID}-" + randomUUID() as String
  print "testing against namespace " + testNamespace

    podTemplate(
    label: 'msbPod',
    containers: [
      containerTemplate(name: 'maven', image: maven, ttyEnabled: true, command: 'cat',
        envVars: [
          containerEnvVar(key: 'KUBERNETES_NAMESPACE', value: testNamespace)
        ]), 
      containerTemplate(name: 'docker', image: docker, command: 'cat', ttyEnabled: true,
        envVars: [
          containerEnvVar(key: 'DOCKER_API_VERSION', value: '1.23.0')
        ]),
      containerTemplate(name: 'kubectl', image: kubectl, ttyEnabled: true, command: 'cat'),
    ],
    volumes: volumes
  ){
    node('msbPod') {
      def gitCommit

      stage ('Extract') {
        checkout scm
        gitCommit = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
        echo "checked out git commit ${gitCommit}"
      }

      if (build) {
        if (fileExists('pom.xml')) {
          stage ('Maven Build') {
            container ('maven') {
              sh "mvn -B ${mvnCommands}"
            }
          }
        }
        if (fileExists('Dockerfile')) {
          stage ('Docker Build') {
            container ('docker') {
              sh "docker build -t ${image}:${gitCommit} ."
              if (!registry.endsWith('/')) {
                registry = "${registry}/"
              }
              sh "ln -s /root/.dockercfg /home/jenkins/.dockercfg"
              sh "docker tag ${image}:${gitCommit} ${registry}${image}:${gitCommit}"
              sh "docker push ${registry}${image}:${gitCommit}"
            }
          }
        }
      }

      // Guard this once it's working
      stage ('Test image') { 
        container ('kubectl') { 
          sh "kubectl create namespace ${testNamespace}"
          sh "kubectl label namespace ${testNamespace} test=true"
          sh "find manifests -type f | xargs sed -i \'s|${image}:latest|${registry}${image}:${gitCommit}|g\'"
          sh "kubectl apply -f manifests --namespace ${testNamespace}"
        }
        container ('maven') {
          try {
            sh "mvn -B verify failsafe:verify"
          } finally {
            step([$class: 'JUnitResultArchiver', testResults: '**/target/failsafe-reports/*.xml'])
            step([$class: 'ArtifactArchiver', artifacts: '**/target/failsafe-reports/*.txt'])
          }
        }
        container ('kubectl') { 
          sh 'kubectl delete namespace ${testNamespace}'
        }
      }

      if (deploy && env.BRANCH_NAME == deployBranch && fileExists('manifests')) {
        stage ('Deploy') {
          container ('kubectl') {
            // sh "find manifests -type f | xargs sed -i \'s|${image}:latest|${registry}${image}:${gitCommit}|g\'"

            def deployCommand = "kubectl apply -f manifests"
            if (namespace) {
              deployCommand += " --namespace ${namespace} "
            }
            sh deployCommand
          }
        }
      }
    }
  }
}
