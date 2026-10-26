# Cosmic
Cosmic is a server emulator for Global MapleStory (GMS) version 83.

## Introduction

Cosmic launched on March 2021. It is based on code from a long line of server emulators spanning over a decade - starting with OdinMS (2008) and ending with HeavenMS (2019).

This is mainly a Java based project, but there are also a bunch of scripts written in JavaScript.

Head developer and maintainer: __Ponk__.\
Contributors: a lot of people over the years, and hopefully more to come. Big thanks to everyone who has contributed so far!

Join the Discord server where most of the discussions take place: https://discord.gg/JU5aQapVZK

### Goals
What we are working towards.
* __Vanilla gameplay__ - stay as close to the original game as possible (within reason).
* __Ease of use__ - getting started should be frictionless and contributing to the project straightforward.
* __Reduce technical debt__ - making changes should be easy without causing unintended side effects.
* __Modern tools & technologies__ - stay appealing by continuously improving the code and the project as a whole. 

### Non-goals
Explicitly excluded from the scope of the project.
* __Custom gameplay features__ - existing custom features will be removed over time and new ones are unlikely to be added.
* __Client development__ - this project is focused on the server. Please go elsewhere for client related questions.
* __Public server__ - there will not be an official Cosmic server open to the public. Feel free to launch your own server __at your own risk__. No support will be provided.

## Project setup

### Contribute
You may contribute to the project in various ways, mainly through GitHub:
* Providing improvements to the code through a [Pull Request](https://github.com/P0nk/Cosmic/pulls) from your own fork. 
* Reporting a bug by creating an [Issue](https://github.com/P0nk/Cosmic/issues).
* Providing information to existing issues or reviewing pull requests that others have made.
* ...and in other ways that I haven't thought of!

### Continuous integration
A GitHub Actions pipeline is set up to run the build automatically when a new pull request is opened or commits are pushed to an existing one. This ensures that the code compiles and all the tests pass.

Once a pull request is merged, a tag with the new version is automatically created.

### Discord integration
Most GitHub activity is pushed to a Discord channel for visibility. This works by leveraging a webhook. The activity includes (but is not limited to): merged commits, created PRs, comments, and new tags.

### Versioning
The project follows the [semantic versioning](https://semver.org/) scheme using git tags.
* *Bug fixes* are treated as PATCH: 1.2.__3__ -> 1.2.__4__
* *General changes or improvements* are treated as MINOR: 1.__2__.3 -> 1.__3.0__
* *Major changes* are treated as MAJOR: __1__.2.3 -> __2.0.0__

## Getting started

### Living Henesys

Henesys has 20 stable town identities and eight additional hunters in The Hill
East of Henesys and Pig Park. Town residents walk to places, browse near NPCs,
take breaks, and occasionally talk to each other. Tobin and AshTrail make trips
through the east portal to hunt and return. **MapleMia**, **PipArcher**, and
**Juniper** can accompany you; they usually start near the central market street.

- Type `@residents` to see nearby names and instructions.
- Double-click a nearby resident to say hello.
- Say `MapleMia, hello` in normal chat, or use `@talk MapleMia hello`.
- Talk about training, mushrooms, travel, or company. Ask `remember me` on a later
  visit: residents remember your character and the last topic you discussed.
- Ask a companion to `follow me` to explore together. They walk, jump, drop down,
  and climb ladders or ropes using the map's terrain. Ordinary open portals carry
  the same companion into the next map after they reach the exit.
- Say `stay` for a break, then `follow me` to resume. Use `@companion` to check
  their location, or `@companion dismiss` to send them home. Logging out, dying,
  changing channels, or using an unsupported warp returns them to Henesys.
- Attack a normal monster while a companion follows to get help. Companions
  assist only monsters already hit by you or your nearby party, and yield to
  other players. They use visible melee attacks, arrows, or Magic Claw according
  to their weapon, with hit reactions and short recovery breaks.
- Independent hunters choose unclaimed normal monsters, pause between hunting
  sessions, and collect private drops from their own kills. Those drops cannot
  enter player inventories. Their damage does not create player EXP or quest
  credit; assisted players retain their actual contribution and drop ownership.
- If a route stays blocked, the companion announces a catch-up and reappears on
  safe ground nearby. This is a timed fallback, not the normal movement path.

Residents use authored, contextual dialogue and need no AI API key. They remember
conversations, recognize acquaintances occasionally, react to nearby level-ups,
and vary their timing. Unsolicited chatter has per-speaker and per-map cooldowns
and suppresses repeated lines. `@residents` identifies them as simulated actors.
Chat uses five consistent voices and Maple shorthand: try `Pip, lvl?`, `party?`,
`got pots?`, `brb`, or `back`. Residents trade named jabs, complain about the grind,
and use casual profanity. Try `Pip, ur trash lol` or `Mia, roast me` for a comeback;
bad drops, lag, and self-deprecating complaints get different reactions. The five
voices range from abrasive grinders to dry, quiet regulars and sarcastic helpers.
Short message bursts are combined into one reply;
follow-up questions and answers keep brief conversational context. Saying `yes`
after a companion offers to come accepts the offer. `hold up` pauses a companion,
and asking an independent hunter `cc pls` gives you two minutes of hunting space
without claiming to switch channels. Actual features are still listed in
`@residents`; ordinary dialogue stays short.
They cannot trade, accept party invites, complete quests, or use boss mechanics.
Scripted, closed, and event portals are excluded. Residents do not occupy player
slots or control monsters. Ordinary activity and hunting sleep in empty maps;
travelers can finish walking to their exit. This is a 28-identity prototype,
not a benchmark of thousands of active bots. Following resumes by request after
a server restart; identities and conversation memories persist, while activity
timers, private collections, and combat health are session state.

Navigation uses static footholds, ladders and ropes. Swimming, flight, and moving
platforms are not yet supported. The Henesys-to-field round trip is covered by
tests using the actual map geometry as well as a browser gameplay recording.

Relationships are saved every five seconds and on shutdown to
`.local/population/memories.properties`. Preserve that file to keep memories.
An unreadable file is left intact, with session-only memory and a server error
log until the file is repaired. JVM properties `cosmic.residents.enabled=false`
and `cosmic.residents.memory=/path/to/memories.properties` disable residents or
change the save location. The browser client's Mac setup includes a patch that
smooths remote-player movement; rerun its setup when installing these changes
on another machine.

### Rebirths on this fork

At level 200, use `@rebirth` (or `@reborn`) in a town and confirm to restart at
level 1 as a Beginner in Henesys. You must be alive and outside parties, events,
trades, shops, and minigames. Unequip non-cash equipment first; it remains in your
inventory and follows its usual level and job requirements. Rebirth preserves base stats, HP/MP, unspent AP/SP, inventory, mesos,
learned skills, and key bindings; temporary buffs are cleared. Your rebirth count
is saved with your character. Job advancement quests reset so you can choose a new
job on the next run; other quests keep their progress.

Use the same command's skill menu to bind a learned active skill from a previous
job to a letter key. The menu asks before replacing the key's existing binding.
Normal weapon requirements still apply, and job-specific passives and special
job mechanics may still require their original job. Passive, event-only, expired,
and GM skills cannot be bound through this menu.

### macOS quick start (Apple Silicon or Intel)

Install [Homebrew](https://brew.sh), then run from this directory:

```sh
./setup-macos.sh
./launch-macos.command
```

Setup installs Java 21 and MySQL 8.4, builds and tests the server, and creates a
database in `.local/mysql`. The launcher uses Java 21 without changing your system
Java, starts the database on `127.0.0.1:3307`, and runs Cosmic. Wait for
`Cosmic is now online`, then follow the macOS instructions in `../Cosmic-client`.
You can also double-click `launch-macos.command` in Finder.

Press **Control-C** in the server terminal to stop it. Stop the database with
`./macos/database.sh stop`; restart it with the launcher. Accounts and characters
persist in `.local/mysql`; keep that directory to preserve your game. Database
logs are in `.local/mysql.log`, and server logs are in `logs/`.

The local database uses the `root` account with an empty password and listens only
on loopback. This setup is for local development. Use
`COSMIC_DB_PORT=3308 ./launch-macos.command` if port 3307 is occupied (stop this
project's database before changing its port). Rebuild after source changes with
`./setup-macos.sh`. Allow about 2 GB of memory for the server; override with
`COSMIC_HEAP=3g` if needed.

For an existing database, use:

```sh
DB_URL='jdbc:mysql://127.0.0.1:3306/cosmic' DB_USER=cosmic DB_PASS=your-password ./launch.sh
```

`DB_URL`, `DB_HOST`, `DB_USER`, and `DB_PASS` override the corresponding YAML
settings; `DB_URL` takes precedence over `DB_HOST`.

Follow along as I go through the steps to play the game on your local computer from start to finish. I won't go into extreme detail, so if you don't have prior experience with Java or git, you might struggle.

We will set up the following:
- Database - the database is used by the server to store game data such as accounts, characters and inventory items.
- Server - the server is the "brain" and routes network traffic between the clients.
- Client - the client is the application used to _play the game_, i.e. MapleStory.exe.

### 1 - Database 
You will start by installing the database server and database client. Then you will connect to the server with the client to create a new database schema.

#### Steps

1. Download and install [MySQL Community Server 8+](https://dev.mysql.com/downloads/mysql/). You will have to set a root password. Make sure you don't lose it because you will need it later.
2. Download and install [HeidiSQL](https://www.heidisql.com/download.php).
3. Connect to the database: 
   1. Open HeidiSQL
   2. Create a new Session: "New" -> fill in your password -> "Save"
   3. Connect to the database: click on your saved session -> "Open"
4. Create a new database schema:
   1. In the opened session, right-click on the session name in the menu on the left
   2. "Create new" -> "Database" -> database name should be "cosmic" -> "OK"
5. Done. The database is now ready. Once the Cosmic server starts, it will create tables and populate some of them with initial data.

### 2 - Server
You will start by cloning the repository, then configure the database properties and lastly start the server.

#### Prerequisites
* Java 21 (I recommend [Amazon Corretto](https://aws.amazon.com/corretto))
* IDE (I recommend [IntelliJ IDEA](https://www.jetbrains.com/idea/))

#### Steps

1. Clone Cosmic into a new project. In IntelliJ, you would create a new project from version control.
2. Open _config.yaml_. Find "DB_PASS" and set it to your database root user password.
3. Start the server. The main method is located in `net.server.Server`.
4. If you see "Cosmic is now online" in the console, it means the server is online and ready to serve traffic. Yay!

Below, I list other ways of running the server which are completely optional.

#### Docker
Support for Docker is also provided out of the box, as an alternative to running straight in the IDE. If you have [Docker Desktop](https://www.docker.com/products/docker-desktop/) installed it's as easy as running `docker compose up`.

Making changes becomes a bit more tedious though as you have to rebuild the server image via `docker compose up --build`.

#### Jar
Another option is to start the server from a terminal by running a jar file. You first need to build the jar file from source which requires [Maven](https://maven.apache.org/). Fortunately, [Maven Wrapper](https://maven.apache.org/wrapper/) is provided so you don't have to install Maven separately.

Building the jar file is as easy as running ``./mvnw clean package`` on macOS/Linux or ``./mvnw.cmd clean package`` on Windows. The project is configured to produce a "fat" jar which contains all dependencies (by utilizing the _maven-assembly-plugin_). Note that the WZ XML files are __not__ included in the jar.

To run the jar, a ``launch.bat`` file is provided for convenience. Simply double-click it and the server will start in a new terminal window. 

Alternatively, run the jar file from the terminal. Just remember to provide the `wz-path` system property pointing to your wz directory.

### 3 - Client
The client files are located in a separate repository: https://github.com/P0nk/Cosmic-client

Follow the installation guide in the README.

### 4 - Getting into the game
You have successfully started the client, and you're looking at the login screen. 

#### Logging in
At this point, you can log in to the admin account using the following credentials:
* Username: "admin"
* Password: "admin"
* Pin: "0000"
* Pic: "000000"

You can also create a new regular account by typing in your desired username & password and attempting to log in. This "automatic registration" feature lets you create new accounts to play around with. It is enabled by default (see _config.yaml_).

#### Entering the game
Create a new character as you normally would, and then select it to enter the game. Hooray, finally we're in!

If you log in to the "Admin" character, you'll notice that the character looks almost invisible. This is hide mode, which is enabled by default when you log in to a GM character. You won't be visible to normal players and no mobs will move if you're alone on the map. Toggle hide mode on or off by typing "@hide" in the in-game chat.

Hide is one of many commands available to players, type "@commands" to see the full list. Higher ranked GMs have access to more powerful commands.

That's it, have fun playing around in game! 

## Advanced concepts
Some slightly more advanced concepts that might be useful once you're up and running.

### Host on remote server
You don't have to host the server on your local machine to play. It's possible to host on a remote server such as a VPS or a dedicated server.

I leave it to you to figure out the server hosting part, but once you have that running you'll need to edit the client ip to point to your remote server ip.

### WZ files
WZ files are the asset/data files required by the client and server. Typically, the [HaRepacker-resurrected](https://github.com/lastbattle/Harepacker-resurrected) tool is used to manage (view, edit, export) the .wz files.

The client can read the .wz files directly, but the server requires them to be in XML format. The server does not make use of the sprites, which is the motivation for different kinds of exporting. 
HaRepacker allows you to export to "Private server", which is the .img files packaged in the .wz stripped of sprites and converted to XML. This takes much less disk space.

This server requires custom .wz files (unfortunately), as you may have noted during installation of the client. The intention is for these to be removed eventually and to solely run on vanilla .wz files.

#### WZ editing
* Use the HaRepacker-resurrected editor, encryption "GMS (old)".
* Open the desired .wz for editing and use the node hierarchy to make the desired changes (copy/pasting nodes may be unreliable in rare scenarios).
* Save the changed .wz, overwriting the original content at the client folder.
* Finally, re-export (using the "Private Server" exporting option) the changed XMLs into the server's .wz XML files (found in the "wz" directory), overwriting the old contents.

Make sure to always export from the client .wz files to the server XML, and not the other way around. 

Editing the client .wz without exporting to the server may lead to strange behavior.
