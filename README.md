# maven-master-project

Main documentation can be found on the core project page: https://github.com/bbottema/simple-java-mail/.

This project only functions to define defaults for all the modules. Each submodule is both a Maven and GIT submodule.

The point of these modules is to create a better user experience where you add Simple Java Mail modules as single Maven dependencies, 
rather than the actually needed collection of 3rd party dependencies.

## Clone project including submodules
git clone --recurse-submodules --jobs 8 https://github.com/simple-java-mail/maven-master-project.git

## Update / init all submodules
git submodule update --jobs 8 --init --recursive

## Splitting out a module from main project

Also see: https://help.github.com/articles/splitting-a-subfolder-out-into-a-new-repository/

1. git clone https://github.com/bbottema/simple-java-mail.git xyz-module
1. git filter-branch --prune-empty --subdirectory-filter "src/main/java/org/simplejavamail/module/parent/folder" -- --all
1. git remote set-url origin https://github.com/simple-java-mail/xyz-module.git
1. git push -u origin origin/develop (or other relevant local repo)
1. clean up repo
1. cd your/system/maven-master-project
1. git submodule add "https://github.com/simple-java-mail/xyz-module.git" "modules/xyz-module"
1. add as module to root pom
1. configure module project as new Maven project with parent POM as maven-master-project
