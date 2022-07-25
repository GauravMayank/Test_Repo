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
def worker_job = env.worker_job
def PRECOMPILE_ENV = env.PRECOMPILE_ENV
//def retry-max-time = env.retry-max-time

def git_clone() {
   stage name: 'app clone repo', concurrency: 5
   //checkout scm: [$class: 'GitSCM', userRemoteConfigs: [[url: 'git@github.com:elarahq/'+GIT_REPO+'.git', credentialsId: 'b5b5b230-4f8a-4213-a6ba-7efccc0ae00c' ]], branches: [[name: TAG]]], poll: false
   checkout scm: [$class: 'GitSCM', userRemoteConfigs: [[url: 'git@github.com:GauravMayank/'+GIT_REPO+'.git', credentialsId: 'test_key-git-ssh' ]], branches: 'master'], poll: false  
}
def release_job() {
  stage name: 'release', concurrency: 5
    //ref: '$CI_COMMIT_SHA'
   if("$CI_COMMIT_TAG" == "master"){
      echo "running release_job for $TAG"
      env.tag_name="v0.$CI_PIPELINE_IID"
      echo "v0.$CI_PIPELINE_IID"
   }   
   else {
       echo "Worker job is not running"
   }
}   
node("dev-mini-housing-jenkins-slave") {
      release_job()

  }
