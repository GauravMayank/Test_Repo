#!/usr/bin/env groovy
import hudson.model.*
import hudson.EnvVars
import groovy.json.JsonSlurperClassic
import groovy.json.JsonBuilder
import groovy.json.JsonOutput
import java.net.URL
import groovy.json.JsonSlurper
import java.text.SimpleDateFormat
//define var for job
def BRANCH_NAME = env.BRANCH_NAME
def TAG = env.tag


def git_clone() {
   stage name: 'app clone repo', concurrency: 5
   //checkout scm: [$class: 'GitSCM', userRemoteConfigs: [[url: 'git@github.com:elarahq/'+GIT_REPO+'.git', credentialsId: 'b5b5b230-4f8a-4213-a6ba-7efccc0ae00c' ]], branches: [[name: TAG]]], poll: false
   checkout scm: [$class: 'GitSCM', userRemoteConfigs: [[url: 'git@github.com:GauravMayank/'+GIT_REPO+'.git', credentialsId: 'test_key-git-ssh' ]], branches: '$BRANCH_NAME'], poll: false  
} 

def build() {
  stage name: 'build docker', concurrency: 5
  try {
     sh "docker build -t ${APP_NAME}:${build_id} -f ${Dockerfile} ."
     println "++++++++++++++++++docker build done+++++++++++++"
    sh "docker tag  ${APP_NAME}:${build_id}  ${imagetag}"
     return "done"
  } catch (all) {
    throw new hudson.AbortException("Some issue with deployment doker build")
  }
}
node("dev-mini-housing-jenkins-slave") {
      build()

  }
