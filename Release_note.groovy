#!/bin/bash
ARGUMENTS="$@"
BRANCH="master"
DRYRUN="0"
GITPARAMS=()
RELEASEDATE=$(date '+%Y%m%d')
RELEASENOTES=""
REMOTE="origin"
PREVIOUS_COMMIT=""
RUNSILENT="0"
VERBOSE="0"
VERSIONTYPE="patch"

def git_clone() {
   stage name: 'app clone repo', concurrency: 5
   //checkout scm: [$class: 'GitSCM', userRemoteConfigs: [[url: 'git@github.com:elarahq/'+GIT_REPO+'.git', credentialsId: 'b5b5b230-4f8a-4213-a6ba-7efccc0ae00c' ]], branches: [[name: TAG]]], poll: false
     checkout scm: [$class: 'GitSCM', userRemoteConfigs: [[url: 'git@github.com:GauravMayank/'+GIT_REPO+'.git', credentialsId: 'test_key-git-ssh' ]], branches: '$BRANCH'], poll: false  
}
def release_job() {
  stage name: 'release', concurrency: 5 
  REPO_DIR=$(echo $(git rev-parse --show-toplevel))
  cd "${REPO_DIR}"

# Get current active branch
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
# Switch to production branch
if [ $CURRENT_BRANCH != "$BRANCH" ]; then
    conditional_echo "- Switching from $CURRENT_BRANCH to $BRANCH branch. (stashing any local change)"
    # stash any current work
    git stash "${GITPARAMS[@]}"
    # go to the production branch
    git checkout $BRANCH "${GITPARAMS[@]}"
fi

conditional_echo "- Updating local $BRANCH branch."
# pull latest version of production branch
git pull $REMOTE $BRANCH "${GITPARAMS[@]}"
# fetch remote, to get latest tags
git fetch $REMOTE "${GITPARAMS[@]}"

# Get previous release tags
conditional_echo "- Getting previous tag."
PREVIOUS_TAG=$(echo $(git ls-remote --tags --ref --sort="v:refname" $REMOTE | tail -n1))

# If specific commit not set, get the from the previous release.
if [ -z "$PREVIOUS_COMMIT" ]; then
    # Split on the first space
    PREVIOUS_COMMIT=$(echo $PREVIOUS_TAG | cut -d' ' -f 1)
fi

conditional_echo "-- PREVIOUS TAG: $PREVIOUS_TAG"

# Get previous release number
PREVIOUS_RELEASE=$(echo $PREVIOUS_TAG | cut -d'/' -f 3 | cut -d'v' -f2 )

conditional_echo "- Creating release tag"
# Get last commit
LASTCOMMIT=$(echo $(git rev-parse $REMOTE/$BRANCH))
# Check if commit already has a tag
NEEDSTAG=$(echo $(git describe --contains $LASTCOMMIT 2>/dev/null))

if [ -z "$NEEDSTAG" ]; then
    conditional_echo "-- Generating release number ($VERSIONTYPE)"
    # Replace . with spaces so that can split into an array.
    VERSION_BITS=(${PREVIOUS_RELEASE//./ })
    # Get number parts, only the digits.
    VNUM1=${VERSION_BITS[0]//[^0-9]/}
    VNUM2=${VERSION_BITS[1]//[^[0-9]/}
    VNUM3=${VERSION_BITS[2]//[^0-9]/}
    # Update tagging number based on option that was passed.
    if [ "$VERSIONTYPE" == "major" ]; then
        VNUM1=$((VNUM1+1))
        VNUM2=0
        VNUM3=0
    elif [ "$VERSIONTYPE" == "minor" ]; then
        VNUM2=$((VNUM2+1))
        VNUM3=0
    else
        # Assume TAGTYPE = "patch"
        VNUM3=$((VNUM3+1))
    fi

    # Create new tag number
    NEWTAG="v$VNUM1.$VNUM2.$VNUM3"
    conditional_echo "-- Release number: $NEWTAG"
    # Check to see if new tag already exists
    TAGEXISTS=$(echo $(git ls-remote --tags --ref $REMOTE | grep "$NEWTAG"))

    if [ -z "$TAGEXISTS" ]; then
        # Check if release notes were not provided.
        if [ -z "$RELEASENOTES" ]; then
            conditional_echo "- Generating basic release notes of commits since last release."
            # Generate a list of commit messages since the last release.
            RELEASENOTES=$(git log --pretty=format:"- %s" $PREVIOUS_COMMIT...$LASTCOMMIT  --no-merges)
        fi
        # Tag the commit.
        if [[ "$DRYRUN" -eq 0 ]]; then
            conditional_echo "-- Tagging commit. ($LASTCOMMIT)"
            git tag -a $NEWTAG -m"$RELEASEDATE: Release $VNUM1.$VNUM2.$VNUM3" -m"$RELEASENOTES" $LASTCOMMIT
            conditional_echo "- Pushing release to $REMOTE"
            # Push up the tag
            git push $REMOTE $NEWTAG "${GITPARAMS[@]}"
        else
            conditional_echo "Release Notes:"
            conditional_echo "$RELEASENOTES"
        fi
    else
        conditional_echo "-- ERROR: TAG $NEWTAG already exists."
        exit 1
    fi
else
    conditional_echo "-- ERROR: Commit already tagged as a release. ($LASTCOMMIT)"
    exit 1
fi

# Switch to back to original branch
if [ $CURRENT_BRANCH != "$BRANCH" ]; then
    conditional_echo "- Switching back to $CURRENT_BRANCH branch. (restoring local changes)"
    git checkout "$CURRENT_BRANCH" "${GITPARAMS[@]}"
    # remove the stash
    git stash pop "${GITPARAMS[@]}"
fi

exit 0

node("dev-mini-housing-jenkins-slave") {
      release_job()
      }
      
