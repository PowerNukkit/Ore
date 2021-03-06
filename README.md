# Ore
![Build Status Ore](https://github.com/PowerNukkit/Ore/workflows/Ore%20CI/badge.svg?branch=staging)
![Build Status Scalafmt](https://github.com/PowerNukkit/Ore/workflows/Scalafmt%20Check/badge.svg?branch=staging)
[![Hydra](https://img.shields.io/badge/%22%22%22%7CHydra-4%20cpus-brightgreen.svg)](https://www.triplequote.com/hydra)

Repository software for Sponge plugins and Forge mods https://ore.spongepowered.org/
 
Ore is written in Scala using the [Play](https://www.playframework.com/) framework.

### Clone
The following steps will ensure your project is cloned properly.

1. `git clone https://github.com/PowerNukkit/Ore.git`   
2. `cp scripts/pre-commit .git/hooks`

### Setup

After cloning Ore, the first thing you will want to do is create a new PostgreSQL 11 database for the application to use.
This is required in order for Ore to run. Learn more about PostgreSQL [here](https://www.postgresql.org/).

You will also need to enable a few extensions for Ore. These are:
* [pgcrypto](https://www.postgresql.org/docs/11/pgcrypto.html)
* [hstore](https://www.postgresql.org/docs/11/hstore.html)

In addition, you need to install Node.js and Yarn. Installation instructions are available for [Node.js](https://nodejs.org/en/download/) and [Yarn](https://yarnpkg.com/lang/en/docs/install).

After setting up a database, create a copy of `ore-default-settings.conf.template` named `ore-default-settings.conf` and
a copy of `ore-jobs-default-settings.conf.template` named `ore-jobs-default-settings.conf` and configure the application,
for the application you want to run. This file is in the `.gitignore` so it will not appear in your commits.
Your local copy needs to get updated every time you pull changes, which add a new setting to the config. Currently valid 
applications are `ore` and `jobs`, and their configuration files can be found in `ore/conf/ore-default-settings.conf.template` and 
`jobs/src/main/resources/ore-jobs-default-settings.conf.template`.

In a typical development environment, most of the defaults are fine. Here are a few you might want to take a look at though.

For `ore`:
* You can disable authentication by setting `application.fakeUser` to `true`.

## Running

Running Ore is relatively simple.

**With SBT**
* Download and install the latest [SBT](http://www.scala-sbt.org/download.html) version.
* Execute `sbt ore/run` in the project root to run the web app.
* Execute `sbt jobs/run` in the project root to run the jobs processing.
* **Note:** You are advised to keep the sbt shell open when doing development instead of starting it for each task. 

**With IntelliJ Community Edition**
* Install the Scala plugin.
* Import the `build.sbt` file.

For `ore`:
* Create a new SBT Task run configuration. Enter `ore/run` in the Tasks field.
* Untick the box that says `Use sbt shell`
* Run it.

For `jobs`:
* Either repeat the process for `ore`, but use `jobs/run` instead of `ore/run`.
* Or, click the green triangle next to `OreJobProcessorMain` when opening up the file.

**Note:** You might encounter a stack overflow exception when compiling ore. This is not unexpected. Just assign 
more stack size to sbt in the way you're starting sbt. `-Xss4m` should be enough. If you're using IntelliJ, you can set 
this in the VM arguments field. If you're invoking sbt directly, the most common ways to set this is either through 
the `SBT_OPTS` environment variable, or with a file named `.jvmopts` with each flag on a new line.

### Using Hydra

Hydra is the world’s only parallel compiler for the Scala language.
Its design goal is to take advantage of the many cores available in modern hardware to parallelize compilation of Scala sources.
This gives us the possibility to achieve a much faster compile time.
[Triplequote](https://triplequote.com/) has kindly provided us with some licenses.
If you have a license and want to use Hydra, follow these steps:

1. Create the file `project/hydra.sbt`
2. Put in this content into the newly created file:
   ```
   credentials += Credentials("Artifactory Realm",
       "repo.triplequote.com",
       "<username>",
       "<password>")
   resolvers += Resolver.url("Triplequote Plugins Releases", url("https://repo.triplequote.com/artifactory/sbt-plugins-release/"))(Resolver.ivyStylePatterns)
   addSbtPlugin("com.triplequote" % "sbt-hydra" % "<version>")
   ```
   - The `<username>` and `<password>` placeholders have to be replaced with your credentials.
   - The `<version>` placeholder has to be replaced with the lastest version of `sbt-hydra` which can be obtained from the [offical changelog](https://docs.triplequote.com/changelog/).

3. Open the sbt console and make use of the following command where `<license key>` is your personal hydra license key:

   ```
   hydraActivateLicense <license key>
   ```

4. Go and start compiling!

Further instructions can be found at the [official Hydra documentation](https://docs.triplequote.com/).
