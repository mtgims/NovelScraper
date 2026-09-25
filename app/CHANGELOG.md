# NovelScraper for Android and Linux, changelog

Newest first.


## 0.47.1, 2026-09-25 · `versionCode 97`
- **A chapter can be asked what it can do.** Right-click one on a computer, hold
  it on a phone: mark the chapters before it read or unread, mark it either way,
  or mark every other chapter in the novel read or unread. Holding a chapter used
  to start picking chapters out, so the menu offers that too.
- **The sources a device has now follow the account properly.** They were sent
  once and never again, and nothing on the receiving side did anything with
  them. Installing or removing a source now tells the other devices, and the
  Extensions screen lists, above Available, the sources an account has that this
  device cannot otherwise see because it does not list their repository.
  Installing one adds the repository and fetches the source. Nothing installs by
  itself.
- **The book page has room to breathe on a computer.** The details column grew
  with the window instead of staying 420dp wide beside an ocean of chapter list,
  chapter rows are no longer padded for a thumb, and the title is no longer
  printed twice: the bar leaves it to the heading and takes it over only once
  the heading has scrolled away.

## 0.47.0, 2026-09-25 · `versionCode 96`
- **An account now does one thing: sync.** The server used to scrape novels,
  store their text, build EPUBs, serve covers and run a web reader. All of that
  happens on the device already, so it has been taken out of the server and out
  of the app. What an account adds is the library kept in step between devices:
  the novels, their order, ratings, categories, read chapters, the reading
  position to the sentence, and which sources are installed.
- **Importing an EPUB happens here, and needs no account.** The file used to be
  uploaded to the server; it is read on the device now, so importing works
  offline, signed in or not. An imported novel stays on the device that
  imported it and is deliberately not synced: the records carry what a library
  holds, not the books themselves.
- **Save as EPUB works for any novel, offline.** The file was built by the
  server and offered only for novels stored there. It is written on the device
  now, from the chapters held there, for any novel with something downloaded.
  A novel is split into volumes of a hundred chapters, as the server split them:
  one volume saves as a single EPUB, several save as a zip holding one EPUB per
  volume. A volume is a fixed window of chapters, so volume 3 is always chapters
  201 to 300 and exporting the same novel twice gives the same files.
- **Exporting the reading statistics no longer needs an account** either, for
  the same reason: the file is written here.
- **Novels that lived on the server have been removed from the library.** They
  cannot be fetched any more, so the rows would only have failed to open.
  Anything read from a source is untouched. A novel that was on the server can
  be brought back by exporting it as an EPUB beforehand and importing the file.
- **The Progress tab is gone**, along with checking a server novel for new
  chapters and the scrape relay. Novels from a source are still checked for new
  chapters, on the device, as before.

## 0.46.1, 2026-09-24 · `versionCode 95`
- **Stats count every novel.** They came from the server, which knows only the
  novels scraped or imported there, so a novel added from a source never
  counted. Books and chapters are now counted in the library itself, words
  from the server for its novels and from the chapter text on the device for
  the rest, and Stats no longer needs an account; only Export, the server's
  file, still does.
- **All four narration engines fit on a phone.** Kokoro, Supertonic, Piper and
  the phone's own voice wrap onto a second line instead of pushing Piper off
  the screen and folding Supertonic's name in two.

## 0.46.0, 2026-09-24 · `versionCode 94`
- **Supertonic, a third narration voice.** Settings → Narration → Engine now
  offers Supertonic 3 beside Kokoro and Piper: a 122 MB download, ten voices,
  nearly as natural as Kokoro and several times lighter. On a laptop's
  processor it reads a chapter at a fifth to a third of real time with the
  whole app at 8% of the machine, where Kokoro needs half of real time and
  four times the processor; with GPU acceleration on, the graphics card takes
  it and the app drops to 2%. That margin is what should let a phone narrate
  with a natural voice. English for now.

## 0.45.1, 2026-09-24 · `versionCode 93`
- **The app no longer freezes while it tries the graphics cards.** In 0.45.0
  the cards were tried the first time Listen was pressed, and while that ran,
  narration and closing the window both waited on it: up to two and a half
  minutes on an integrated chip. The cards are now tried in the background as
  soon as GPU acceleration is downloaded or switched on, with progress shown
  in Settings; until there is an answer the processor reads, and the next
  time narration starts it moves to the card. Closing the app never waits on
  narration for more than a moment.

## 0.45.0, 2026-09-24 · `versionCode 92`
- **GPU narration on Windows for every graphics card, in a 343 MB download
  instead of 2 GB.** Windows now uses DirectML, which runs on AMD, Intel and
  NVIDIA cards alike, in place of CUDA; Linux keeps CUDA for NVIDIA cards. The
  pack brings a copy of Kokoro made to run on DirectML, with the same voice.
  On a GTX 1060, 0.12 of real time once warm, with the processor nearly idle.
- **The fastest card is found by trying.** DirectML takes the first adapter
  Windows lists, which on most laptops is the integrated chip, and there
  Kokoro can run twelve times slower than real time. The first time a voice
  is used, each card is tried in a separate copy of the app and the fastest
  one that keeps ahead of the voice is kept; if none does, the processor
  narrates and Settings says so.
- **A card that fails can't take the app down.** Some graphics failures end
  the whole process instead of reporting an error, so a voice is only ever
  narrated on a card after that separate copy has spoken with it.
- **GPU files from 0.44.0 can be removed** from the same place in Settings.
- **Settings are written far less often.** Every change rewrote its whole
  file, fifty times for one drag of a slider; now a save waits for the ones
  already queued.

## 0.44.0, 2026-09-24 · `versionCode 91`
- **Narration on an NVIDIA graphics card.** Settings → Narration → GPU
  acceleration appears where the driver reports a card CUDA 12 can use, and
  downloads what the card needs: about 2 GB, 2.6 GB once unpacked, every file
  checked against its published checksum. Switched on, the neural voices run on
  the card: on a GTX 1060, three times faster than four processor cores, with
  the whole app at 2% of the machine while it reads aloud. If the card or its
  driver turns the model down, narration uses the processor as before and
  Settings says why. The pack can be removed from the same place. On Linux the
  same pack is offered but has not yet been tried on a machine.

## 0.43.0, 2026-09-24 · `versionCode 90`
- **Updates on Windows no longer delete the app's data.** The installer puts
  the program in `%LOCALAPPDATA%\NovelScraper` and removes that folder whole
  before installing a new version, and the library, the installed sources, the
  downloaded voices and the browser's profile were kept in the same folder.
  They now live in `%LOCALAPPDATA%\NovelScraper Data`, which no installer
  touches; settings stay in `%APPDATA%\NovelScraper`. The update to this
  version still runs the old version's installer, so it clears the old folder
  one last time: a library kept on the server comes back by itself, and
  sources and voices have to be installed again.
- **Narration on the desktop takes half the processor.** Kokoro is no faster
  past about four threads, and it had been given up to eight, each extra one
  burning processor while it waited: on an i7-8750H, the same speed for 1.9
  CPU-seconds per second of audio instead of 4.2.

## 0.42.1, 2026-09-24 · `versionCode 89`
- **Unused code removed.** Two calls to the server that nothing made any more,
  with their request bodies, and a handful of members no screen read. Nothing
  the reader sees or does changes.

## 0.42.0, 2026-09-24 · `versionCode 88`
- **The window behaves like a Windows window.** It had no system frame at all,
  so it could not be dragged by its title bar, vanished when minimised, jumped
  when maximised and restored, and its edges could not be taken hold of. It now
  has the system's frame with only the title bar drawn by the app: it drags and
  snaps, minimises, maximises and restores with the usual animations, resizes
  from every edge and corner, and has the rounded corners and shadow of the rest
  of the desktop.
- **No browser window for sites like Scribble Hub on Windows, not even in the
  taskbar.** The browser those sites need runs on a desktop of the app's own:
  a real, painted window, which is what the site's check wants, that appears
  nowhere. A check that does want a person still comes to the reader's screen,
  and that browser is closed as soon as it has been answered. Closing it now
  lets it save what it earned, so the same check is not met again straight away.
- **Much less memory on the desktop.** Java's defaults let the heap grow toward
  a quarter of the machine's memory and kept a fifth of that for decoded covers.
  The heap now has a ceiling and gives back what it stops using, and the image
  cache is capped at 48 MB: about 240 MB at startup instead of 360, and well
  under 400 MB after browsing a source's covers where it used to pass 590.

## 0.41.4, 2026-09-24 · `versionCode 87`
- **No browser on screen for sites like Scribble Hub.** Such a site refuses this
  app's own client whatever cookies it carries, so its pages are fetched through
  a browser; what the site's check insists on is a window that is being painted,
  not one anybody looks at. The browser is now given an X server of its own with
  no monitor behind it: the check passes by itself in a couple of seconds and
  nothing appears on the desktop. Where there is no Xvfb, the window is parked
  far off the side of the screen rather than minimised, because a minimised
  window stops being painted, which is what left checks looping and ending up on
  screen to be answered by hand. A check that does still want a person is moved
  to the reader's screen for that one answer, and closed ten seconds later.

## 0.41.3, 2026-09-24 · `versionCode 86`
- **A maximised window on Windows leaves the taskbar where it is.** A window
  that draws its own bar has nothing telling it where the usable screen ends, so
  it maximised over everything.
- **Updates on Windows install themselves.** No installer to click through for
  every version, and no errors about files the app still had open: the app steps
  aside, the installer runs on its own, and the new app starts.
- **The browser for sites like Scribble Hub stays out of sight and does not
  stand around.** A site whose check once wanted a person had its window put on
  screen at every request afterwards; now the check gets a couple of seconds to
  pass unseen first, and a site that passes on its own stops bringing a window
  up at all. The window is put away after every page, and a browser nothing has
  needed for a minute is closed rather than kept for three.

## 0.41.2, 2026-09-24 · `versionCode 85`
- **The window wears the app's own title bar on Windows**, in whichever theme is
  chosen, with the minimise, maximise and close buttons where Windows puts them,
  dragging where a title bar drags and a double-click to maximise. The system's
  own white bar above a dark app is gone. `NOVELSCRAPER_TITLEBAR=1` turns the
  same bar on elsewhere.
- **It is called NovelScraper.** A task manager listed it as "read and listen to
  web novels", which was the description sitting where a program's name belongs,
  and it installed itself into a folder named in lower case.

## 0.41.1, 2026-09-24 · `versionCode 84`
- **The Windows installer no longer carries Linux libraries.** It shipped JOGL's
  Linux native libraries, which Windows cannot load and never needs, since the
  carried browser is only reached where no browser is installed and Windows
  always has Edge.
- A second Windows build is published beside the first, `novelscraper-setup-
  console.exe`, which keeps a console window. The ordinary launcher reports any
  failure as "failed to launch JVM" and nothing else; this one prints what
  actually went wrong.

## 0.41.0, 2026-09-24 · `versionCode 83`
- **A Windows app.** The same code, packaged as a per-user installer that needs
  no administrator and puts NovelScraper in the start menu. What differed by
  platform is now told apart properly: browsers are looked for under Program
  Files rather than on a PATH that never has them, the Chrome the app fetches
  for machines without one comes in its Windows build, narration uses the
  Windows speech library, the media keys stay a Linux affair, and updating runs
  the downloaded installer instead of swapping an AppImage.
- Both desktop packages are now built by a workflow, each on the system it
  needs, because the tool that makes them wraps the running platform's own
  runtime and cannot cross-compile.

## 0.40.0, 2026-09-24 · `versionCode 82`
- **The app updates itself.** It asks the releases page what the newest version
  is, quietly at startup and on demand under Settings, and offers to fetch it.
  On the phone the downloaded package goes to the system installer, which asks
  before anything is replaced; on Linux the AppImage is swapped for the new one
  and the app restarts. Neither replaces anything without being told to, and
  nothing about it requires going to a web page and finding a file.

## 0.39.3, 2026-09-24 · `versionCode 81`
- **The phone app stays upright.** Laid on its side, a phone is wide enough in
  density-independent pixels to cross the width where the layout switches to the
  one meant for a window on a computer, sidebar and all. It is locked to
  portrait now.

## 0.39.2, 2026-09-23 · `versionCode 80`
- **Novels can be dragged again on a phone.** The right-click menu took the long
  press for itself, and a long press is how a finger picks a novel up. The menu
  is now a pointer's alone; what it offers is on the novel's own page, which is
  where a phone has always found it.
- **Sync says what is actually wrong.** "Can't reach the server" was shown for
  any failure at all, including the one that is happening: the server answers
  perfectly well but has no sync in it, because it is running a build from
  before sync existed. That now reads as what it is, and points at updating the
  server.

## 0.39.1, 2026-09-23 · `versionCode 79`
- **Fixed: Browse, Progress, Stats and Settings crashed the app.** Taking the
  Scrape destination out took the destinations that sat below it in the same
  file with it, so the sidebar offered five places the app no longer knew how to
  reach. They are back, and every one of them is now opened in turn before a
  build goes out.

## 0.39.0, 2026-09-23 · `versionCode 78`
Novels come from sources now, so the app stops asking for web addresses.
- **Scrape is gone.** Pasting a novel's address and having the server go and
  fetch it was how novels got in before there were sources. A novel now comes
  from Browse and is kept with the Download button on its own page, or with
  "Download all chapters" on a right-click in the library. The NovelUpdates
  browser that existed to feed the scraper went with it.
- **Importing an EPUB moved to the library**, where the novels are, rather than
  living under Scrape. It is unchanged otherwise and still needs a server.
- **Long novels are split into parts of a hundred chapters.** A novel from a
  source arrives as one run, however many there are, and reaching chapter nine
  hundred meant scrolling to it. Those runs are now cut into collapsible parts
  ("CHAPTERS 101-200"), the way a novel with real volumes already was, and the
  part being read is the one that opens.

## 0.38.6, 2026-09-23 · `versionCode 77`
- **A right-click opens its menu where the pointer is.** It was appearing at a
  fixed spot beside the novel, because a dropdown measures from the bottom of
  whatever it is attached to and it was attached to the whole tile. It now hangs
  off a point of no size sitting exactly where the press landed, whether that
  press was a right-click or a finger held down.

## 0.38.5, 2026-09-23 · `versionCode 76`
- **The sidebar opens and closes rather than jumping.** It widens and narrows
  over about a fifth of a second, and the labels come and go with it, taken away
  from their far end like a panel closing over them. The icons keep their place
  throughout: they sit in a slot of their own at the start of each row, which is
  what makes it read as the panel moving rather than everything rearranging.

## 0.38.4, 2026-09-23 · `versionCode 75`
- **The browser doesn't sit there eating memory.** A whole Chromium, with its
  own graphics, network and storage processes, was kept standing by for the
  whole session, which is about a gigabyte for a reader who has settled into a
  chapter. It now closes after three minutes with nothing asked of it; what it
  has earned lives in its profile, not its memory, so the next page starts it
  again.
- **And it doesn't outlive the app.** A browser was only closed when the app
  closed its window; a crash, a kill or a terminal interrupt left the whole tree
  running with nobody driving it, for ever. It now goes with the app whatever
  ends it, and anything found still running on the app's own profile is cleared
  away before a new one starts.
- **It runs leaner** while it is up: no extensions, no sync, no background
  updates, no crash reporter, and at most two page processes.

## 0.38.3, 2026-09-23 · `versionCode 74`
- **The themes reach the whole app now.** Each palette named a handful of
  colours and left the rest to Material, whose own are purple: that is why the
  sidebar stayed the same mauve in every theme, and why a selected row did too.
  All four themes now carry their own surfaces and accents, so the sidebar is
  neutral in Dark, navy in Blue, plum in Purple and near-white in Light.
- **Chapters from a source read as chapters.** Their paragraphs arrive as plain
  line breaks, which runs the page together into a wall of text. Each paragraph
  is now its own block, with space after it and an indent on its first line, as
  a book has. The sentence a narration is on is unaffected, because the split
  falls between sentences.
- The sidebar's collapse control lost its label: three lines at the top of a
  sidebar have meant that for thirty years, and the word took up the very room
  it exists to reclaim.

## 0.38.2, 2026-09-23 · `versionCode 73`
- **Kokoro sounds like Kokoro now, on a computer.** The app was fetching the
  model quantised down to eight bits, a quarter of the size and audibly so: the
  right voice with gravel poured over it. The Linux app now fetches the
  full-precision one (about 335 MB), which on this machine speaks four seconds
  of narration in under a second, five times faster than it is listened to. The
  phone keeps the small model, where the storage and the processor both matter.
- **Library is reachable from a novel's page again.** It was the one destination
  in the sidebar that did nothing there: going to it asked to pop back to the
  library and then to open the library, and the two cancelled each other out.

## 0.38.1, 2026-09-23 · `versionCode 72`
The desktop layout, gone over again.
- **A novel lights up whole.** The mark under the pointer was a square patch the
  size of the cover, behind a card that had grown a little larger than it. The
  tile itself now lights, cover, title and all, in the same rounded shape as
  everything else, and nothing grows.
- **Opening a novel no longer lurches.** The side navigation used to disappear
  on the way in, so the page arrived narrow and widened a moment later. It stays
  now, as a sidebar should; only the reader takes the whole window.
- **The novel's page is the same colour as the rest of the app**, and the shade
  it used to be painted in has gone to the chapters, which are now rounded rows
  sitting on it, lit under the pointer, with the current one picked out.
- **The sidebar collapses.** The app's name at the top has become the control
  that narrows the bar to its icons and widens it again, remembered between runs.
- Scrollbars in the novel's details and its chapter list.

## 0.38.0, 2026-09-23 · `versionCode 71`
The Linux app stops being a phone app on a monitor.
- **Navigation down the side.** A row of small targets floating at the bottom is
  for a thumb that finds it without looking; a pointer has to be aimed at it.
  Destinations now live along the left edge, always in the same place, each one a
  full row wide enough to hit without care, lit as the pointer crosses it, with
  the names shown when the window is wide enough. Phones keep the pill.
- **Covers at whatever size suits.** Plus and minus in the library's header,
  remembered between runs, from small enough for a wall of books to large enough
  to read the titles across the room.
- **The library stops jumping.** Entering it started a check for new chapters,
  the refresh button became a spinner, the header changed height and the whole
  grid bounced. The header now keeps its height whatever is in it.
- **Novels can be dragged with a pointer.** Rearranging needed a press and hold,
  which is how a finger avoids scrolling the grid; a pointer just drags.
- **Right-click a novel** for open, mark all read, remove downloads and remove
  from library, instead of opening the novel to find them. Holding does the same
  on a phone.
- **Scrollbars**, where the platform has them, so there is some sense of how much
  is below and something to grab.
- **Settings, Browse and Progress keep to a readable column** rather than a line
  of text with a metre of nothing after it.
- Screens fade between each other on a computer instead of sliding in from the
  side, which was the phone showing which way it went.

## 0.37.3, 2026-09-23 · `versionCode 70`
The browser that wouldn't get out of the way, and what happened when it was
closed.
- **On a tiling desktop the browser now goes to a workspace of its own.**
  Hyprland places every window itself, has no notion of minimising, and ignores
  a window asking to be put anywhere, so the app's browser sat in the middle of
  the way all session. It is now sent, by its own window address so no other
  window is touched, to a workspace kept aside, and that workspace is brought
  into view only when a check needs answering. Other desktops still minimise it.
- **Closing the browser no longer stops the app reading anything.** It noticed a
  browser it had started, and a browser it had taken up with, but not one that
  had gone away: every request after that quietly went nowhere. It now sees the
  connection drop and gets itself another browser. There is a test for it that
  closes the browser behind the app's back.
- Running the app's browser without a window at all was tried and does not work:
  a browser with no window has no graphics, and a check reads that as something
  other than a person's browser.

## 0.37.2, 2026-09-23 · `versionCode 69`
Sources behind a browser, at something like normal speed.
- **Pages are asked for from inside the site, not loaded one by one.** Landing
  on a site cost a whole page load, drawn and waited on, for every single
  request: a listing, a search, each chapter. Now one page of the site is opened
  per session, and everything after it is asked for from inside that page, the
  way the site's own scripts ask. A chapter went from about five seconds to
  about one; Novel Hall's search went from timing out at ninety seconds to
  answering in a couple.
- **Pages a site only hands to a browser going to them** (Scribble Hub's chapter
  pages refuse anything else) are loaded in a frame of the page instead, which
  is still a browser going to a page, and still beats loading the window.
- **A site that stops refusing us goes back to plain requests.** Being written
  down as needing a browser was for ever; now a host gets a plain request again
  every half hour, and is taken off the list the moment one works.
- **The check window is minimised, not shoved off-screen.** Where a window sits
  is a request a desktop can refuse, and a Wayland one always does, which is why
  the browser stayed on screen after a check. Minimising is honoured everywhere.
- Fixed: a site answering on both `example.com` and `www.example.com` was
  treated as two sites, so every request to it took the slow way round.

## 0.37.1, 2026-09-23 · `versionCode 68`
- **The app stops steering a browser window it didn't open.** When it finds a
  browser already running on its own profile (one opened by hand, or one left by a
  run that ended badly), it now opens a window of its own in it and parks that
  out of sight, instead of taking over the tab already in front. Closing that
  window no longer stops the app fetching, and it is left where it was.
- The live source check can search now, not only list and read.

## 0.37.0, 2026-09-23 · `versionCode 67`
Scribble Hub, read in the app at last, and why it wouldn't be.
- **The app's browser no longer announces itself as automated.** Chromium sets
  `navigator.webdriver` on every page while a program is attached to it, and a
  check that wants a person refuses a browser that says it is being driven: the
  same window, opened by hand, passed first time. The app's browser is an
  embedded one with a reader in front of it, answering checks by hand, which is
  what the phone's WebView already looks like to a site. The desktop now matches
  it. Measured: with the flag, the check that had looped for days handed over
  the page.
- **Requests the site's own scripts make.** A chapter list is often fetched by
  the page rather than sitting in it, and such a request is refused unless it
  comes from the page too. Those now go through the browser as the site's own
  script would, forms and all, on both desktop and phone. Scribble Hub's
  chapters arrive through exactly this.
- **Pages are read once they hold still.** A page's load event is not the end of
  it: Scribble Hub handed back 817 characters of shell, filling itself in four
  seconds later. Waiting for it to settle turns that into the whole page.
- Cookies failing to be filed away no longer throws out the page they came with.

## 0.36.2, 2026-09-23 · `versionCode 66`
- **The check window says what it is.** A browser appearing on its own explained
  nothing; the app now says which site is asking and that the answer goes in
  that window.
- **No second browser.** Closing the check window used to start the carried
  browser instead, which opened a black window and could pass nothing anyway.
  When there is a real browser, it is the only one used.
- **A log file**, `~/.cache/novelscraper/novelscraper.log` (last megabyte), and
  the browser now notes what it has to draw with: `graphics: renderer=…,
  webgl=…`. A check judges a browser mostly on its graphics, so that line is
  the first thing to read when one won't pass.

## 0.36.1, 2026-09-23 · `versionCode 65`
Why a check that passes in an ordinary browser wouldn't pass in the app's.
- **A browser left behind blocked the next one.** If the app ended badly, its
  browser kept running and kept the profile, and every later run failed to start
  one and quietly fell back to the old browser it carries, which no hard check
  accepts. It now takes up with the browser already running on its profile, and
  if that one won't say where it is listening, ends it and starts again.
- **A window out of sight could count as hidden.** A desktop that decides the
  off-screen window is covered makes Chromium report the page as hidden, and a
  check does not finish in a hidden page: it waits, gives up and starts over,
  which is exactly what a check that never ends looks like. That judgement is
  now turned off for the app's browser.
- A check that hasn't passed brings its window forward after six seconds rather
  than twelve.
- `NOVELSCRAPER_BROWSER_VISIBLE=1` keeps the browser window on screen from the
  start, for a desktop where a check still won't finish out of sight.

## 0.36.0, 2026-09-23 · `versionCode 64`
Checks that wouldn't finish, and machines with no browser to finish them in.
- **A browser of the app's own, when the computer has none.** Rather than the
  Chromium the app used to carry (Chrome 126, two years old, which the harder
  checks refuse on sight), it fetches the current Chrome that Google publishes
  for driving from a program, about 190 MB, once, and drives it exactly as it
  drives an installed one. Machines with Chromium, Brave, Chrome, Edge or
  Vivaldi keep using that and download nothing.
- **A check that sat there restarting itself.** The page is asked for again
  every few seconds while the window is still hidden, and once more the moment
  the window comes on screen, because some checks never finish in a window that
  isn't being drawn. A site that has needed a person before gets its window
  straight away next time instead of a silent minute first.
- **The page is read out of the document now**, rather than by running script
  inside it. Asking a page to evaluate an expression is the ordinary way to do
  this, and also the way an automated browser announces itself to the checks it
  is trying to get through.
- **A clearer answer when a check keeps asking.** A site that hands over its
  pass and then asks again is objecting to where the request comes from, not to
  the app, and now says so instead of "the check didn't pass".

## 0.35.0, 2026-09-23 · `versionCode 63`
Browser checks, answered by the browser already installed.
- **The installed browser does the check now.** On Linux the app drives the Chromium,
  Brave, Chrome, Edge or Vivaldi already installed, over its debugging
  connection, in a profile of the app's own under the app's data folder, so
  ordinary browsing is untouched. The window stays parked off-screen while a site
  behaves, and comes to the front only when a check wants a person. The browser
  the app used to carry is Chrome 126, from two years ago, and a browser that
  old is held against it by exactly the checks it has to pass; it is still
  there as a fallback for machines with no browser of their own.
- **Ranobes comes through.** Its guard hands over after one pass in a real
  browser, and the cookies it leaves make the requests after it ordinary ones.
  Scribble Hub now gets the clearance the carried browser never did, though its
  pages keep going through the browser, because Cloudflare ties that clearance
  to the browser that earned it.
- **A check passed stays passed.** What a check leaves behind is written down
  and read back on the next run, along with which sites need a browser at all,
  so a site that let the app in yesterday doesn't ask again today.
- **The phone tells the truth about itself.** Android used to claim a fixed
  Chrome 126 while running whatever WebView the phone has; it now says what it
  actually is, which is one less thing for a check to hold against it.
- Nothing is downloaded for a check when a browser is already on the machine.

## 0.34.3, 2026-09-23 · `versionCode 62`
Two sources that wouldn't behave.
- **Ranobes loads again.** It is guarded by DDoS-Guard rather than Cloudflare,
  and answers a plain request with a 503 the app read as a dead site instead of
  a check to pass. It now recognises that guard and loads the page through the
  browser, showing the check when it wants a tick.
- **Novel Hall shows its covers.** Its list pages carry no cover images, and the
  source was filling the gap with a picture that says "book cover not
  available". It now leaves the cover empty, so the app draws the novel's own
  tile, and the real cover appears on the novel's page.
- **A check, once.** Cookies a page collects on its way through the browser are
  kept, so the pages after it are fetched as ordinary requests, at the speed of
  a request rather than a page load.
- Building no longer kills a running Linux app: the APK and the AppImage are
  written beside the old file and moved into place, rather than over a file the
  running app is reading itself out of.

## 0.34.2, 2026-09-23 · `versionCode 61`
Browser checks on Linux now work the way they do on the phone: one browser is
kept out of sight and used to load pages from sources that refuse the app's
plain requests. If a check doesn't pass by itself in a few seconds, its window
comes to the front for the box to be ticked, and waits five minutes; it
disappears again as soon as the page comes through. The browser is kept for the
rest of the session, so a source only asks once. It is no longer started with
its graphics turned off: a browser without WebGL looks like a bot to exactly
the checks it has to pass.

## 0.34.1, 2026-09-23 · `versionCode 60`
The Linux app no longer dies when a source asks for a browser check. Chromium
was told to draw the way a Wayland desktop does, while the window it was given
is an X11 one (Java's windows always are, through XWayland), and it was also
looking for its own helper programs beside the app's Java runtime instead of in
the downloaded browser. Either one took the whole app down. It now draws
off-screen into the app's own window, on X11, with the right paths, and refuses
to start at all (with a message) when there is no display to draw on.

Also: pages that a site refuses to hand to the app are now loaded through that
browser instead, and cookies it collects keep the domain and path the site set
them for. This gets through some sites, but not the ones whose check only
passes for a browser window on screen, Scribble Hub among them: for those the
app still says so and points at the phone or an EPUB. See the note in
CHANGELOG.md.

## 0.34.0, 2026-09-23 · `versionCode 59`
The rough edges, smoothed.
- **Keep scrolling to the next chapter.** At the end of a chapter, carrying on
  moves to the next one (and pulling down at the top goes back), with a line at
  the end saying so. Narration already did this; now reading does too.
- **New chapters, found automatically.** Opening the library looks over source
  novels for chapters that appeared since last time (at most every six hours,
  one novel at a time so no site gets a burst), and the refresh button checks
  now and says what it found. New chapters show as the unread count.
- **Downloads that don't give up.** A chapter that fails is tried three times,
  then set aside so the rest carry on; the novel's page says how many were set
  aside, with "Try again" and "Forget". If everything is failing, the queue
  waits instead of hammering the site. On Android, downloads now keep going
  after the app is left, with a notification.
- **A copy before the library changes shape.** Whenever an update changes the
  library's schema, the old file is copied aside first (the newest two are
  kept), so a bad migration can't take the library with it.
- **Browser checks on the desktop.** When a source asks for a browser check,
  the Linux app can now open a real browser, let the check run, and carry on
  with what it collected. Chromium is fetched on first use (about 500 MB, into
  the app's data folder), not shipped in the download. The phone answers checks
  with its own WebView.

## 0.33.0, 2026-09-22 · `versionCode 58`
Narration, the way it should have been.
- **No gap between chapters.** A novel now plays straight through: the next
  chapter is fetched and its first sentences are made while the current one is
  still being heard, into the same audio stream. The reader follows what is
  being read, chapter by chapter.
- **Asides are passed over.** Site plugs, patron and chat links and translator
  notes aren't read aloud, and further lines to skip can be added (Settings,
  Narration). Nothing is hidden from the page: only narration skips them.
- **Pronunciation.** Tell the app how a word should sound ("Xianxia" →
  "shyen shya") and every voice says it that way.
- **Sleep timer.** 15, 30, 45 or 60 minutes, or "end of chapter", in the
  narration panel.
- **Save as audio.** A chapter, the next ten or a whole novel read out to WAV
  files in Downloads, with the same voice and settings, so they play in a car or
  on any player. About 3 MB a minute, and slower than listening: it runs in the
  background with progress and a cancel.
- **Media keys on Linux.** Play/pause, next and previous work from the keyboard
  and from the desktop's media widget (MPRIS), with the chapter's title showing.
- Under the hood, Android's narration now runs the same code as the desktop's
  for the on-device voices, so both get all of this at once. Chapters count as
  read when they are heard, in order.

## 0.32.0, 2026-09-22 · `versionCode 57`
Devices now keep the same library.
- **Sync.** Signed in to a server, the phone and the desktop share the novels
  in the library, their order, ratings, collections, which chapters have been
  read, and the position in the one being read. A novel added on one
  device appears on the other (with its source; install that source there to
  read it). The server stores this metadata only, never chapter text.
- **The reading position, to the sentence.** Stopping mid-chapter on one device
  and continuing on the other opens at the same line, whatever the screen size,
  and narration counts as reading: pausing it sets the resume point too.
- **Offline changes are kept.** Anything read, rated or shelved without a
  connection is sent when there is one again. When two devices changed the same
  thing while apart, the later change wins, chapter by chapter, so marks made on
  both sides all arrive.
- Settings, Server account shows when the library last synced, with a Sync now
  button; the library's refresh does both.
- A novel whose source isn't installed here says so, instead of a fetch error.

## 0.31.1, 2026-09-22 · `versionCode 56`
The Linux app starts again: 0.31.0's AppImage closed at once on startup, as its
bundled Java runtime lacked the database module (`java.sql`) the new library
needs. The Android app is unchanged.

## 0.31.0, 2026-09-22 · `versionCode 55`
The library now lives on the device, and an account is optional.
- **Read from sources without a server.** Opening a novel in Browse shows the
  same novel page as the library (cover, summary, chapters, progress), with
  **Add to library**. Its chapters stream from the site and are cached; the
  reader is the full reader, so narration, fonts, resume and read marks all work
  for source novels too.
- **Download** keeps chapters for offline reading and listening: the next 10,
  every unread one, or the whole novel. Downloads run one chapter at a time with
  a pause between them, survive a restart, and show on the novel's page (with
  Cancel) and on its library card. Remove downloads from the novel's menu.
- **The library is local** (novels, chapters, text, progress, ratings,
  collections), so it opens instantly and works offline. Library cards show the
  unread count and a mark when chapters are downloaded.
- **The server account is optional** and lives under Settings, Server account.
  Signed in, the server's novels are brought into the library (with their
  progress, ratings and collections) and kept in step: progress, ratings and
  shelf changes made here are sent to the server, and ones made offline are
  sent when it can be reached again. Without an account, Scrape, Progress and
  Stats ask for a sign-in. A saved sign-in no longer lands on the login
  screen when the server can't be reached.
- Under the hood: SQLDelight, with the schema pinned to Android 8's SQLite.

## 0.30.2, 2026-09-22 · `versionCode 54`
Nothing new to see; the build underneath changed. The app now builds with Kotlin
2.4.20, Compose Multiplatform 1.12.0, Android Gradle Plugin 9.4.1, Gradle 9.7.1
and compileSdk 37 (targetSdk stays 34). The Android app is its own module
(`androidApp`, with the Application, the activity and the packaging) on top of the
shared code (`composeApp`, now a Kotlin Multiplatform library that also builds the
Linux app). The version moved to `gradle.properties`, the phone build is
`./gradlew :androidApp:assembleRelease`, and Android lint now reads all the code,
which caught one real bug: an extension using `urlencode` crashed on Android 12
and older (it called a method that only exists from Android 13); fixed.

## 0.30.1, 2026-09-22 · `versionCode 53`
Sources now come only from repositories added by hand. The app ships with no sources
and no repositories: under Browse, Extensions, Repositories, add the address of a
repository's index.json and installs from it, the way LNReader
works. NovelScraper's own sources, all 15 of them, live in their own repository,
github.com/mtgims/novelscraper-extensions; add
`https://raw.githubusercontent.com/mtgims/novelscraper-extensions/master/index.json`.
LNReader's repository, or anyone's in the same format, can be added the same way.

## 0.30.0, 2026-09-22 · `versionCode 52`
**Source extensions.** A new Browse tab reads novels straight from their sites
through extensions, on the phone and on Linux:
- LNReader's plugins work as they are: its repository (280 sources, kept up by
  its community) is listed under Browse, Extensions, where sources are installed,
  updated and removed; other repositories in the same format can be added. The
  app runs each plugin in its own JavaScript engine (QuickJS) with the same
  libraries LNReader gives them.
- The app's own sources ship as built-in extensions in that format: Novel Archive,
  Novel Bin (novel-bin.com), OpenQuill, Ranobes (ranobes.net) and Wuxia Click.
- A source shows its popular and latest novels and searches them; a novel's page
  has its details and chapters (side by side in a wide window), and chapters open
  in a reader with the library reader's type and measure (arrows move between
  chapters on a keyboard).
- Requests go out from the device and its own connection. A site that shows a
  browser check (Cloudflare) is reported as such; passing those checks comes
  later.

Reading from a source doesn't add anything to the library yet, and narration
works on library books only for now; both come with the local library.

Under the hood: Kotlin 2.3.21 and compileSdk 36 (the JavaScript engine needs
them). APK 35.0 MB (0.29.0: 33.8 MB).

## 0.29.0, 2026-09-21 · `versionCode 51`
**The Linux app.** The same app as on the phone, as a desktop window, against the
same server: sign in, library, collections, book page, reader, ratings, volume
downloads, EPUB import, progress export, and narration with the Kokoro and Piper
voices (downloaded in Settings; there is no system voice on Linux, so those two
are the engines). Built as one AppImage file.
- Made for a wide window: the book page shows details and the chapter list side
  by side above ~900 dp, the reader keeps a comfortable text column, and the
  reader takes keys: ←/→ chapters, Space/Page Down and Shift+Space/Page Up turn
  the page, P plays or pauses, Ctrl +/- font size, Esc goes back.
- Scrapes relay through the computer's connection, as they do through the phone.
  The NovelUpdates browser needs Android's web view, so it isn't offered on Linux
  yet; paste the translator's link instead.
- Sentence splitting is byte-for-byte the phone's (a port of Android's HTML
  converter, checked against the real one on test pages and real chapters), so
  reading positions and narration line up across devices.

On the phone:
- In landscape and on tablets, the reader's text column, the narration player
  and the sign-in form now keep their intended widths instead of stretching edge
  to edge.
- Tablets wider than ~900 dp get the side-by-side book page too.

## 0.28.3, 2026-09-21 · `versionCode 50`
Importing an EPUB the app itself downloaded works. Android lists a download by
its title ("Renegade Immortal · volume 1"), not its file name, and the server
turned the upload away for not ending in .epub. The picker only offers EPUB
files, so the name now gets the extension when it lacks one.

## 0.28.2, 2026-09-21 · `versionCode 49`
No visible change: the groundwork for the Linux and Windows apps. The project
moved from `APKcode/` to `app/` and became Kotlin Multiplatform, one module
(`composeApp`) with a shared JVM source set that holds everything not tied to
Android: models, networking, view models, screens, theme, the reader. What is
Android-only (the narration service, WebViews, DownloadManager, file pickers,
system bars, fonts) sits behind small `expect`/`actual` declarations, and the
desktop target already compiles against them. Settings keep the same storage
names and keys, so installing over 0.28.1 keeps the login, theme, font size and
reading positions. Toolchain: Kotlin 2.2.21, Compose Multiplatform 1.10.3,
AGP 8.13.2 (the old AGP's lint could not read Kotlin 2.2), Gradle 8.14.3, and
Coil 3 for images, still honouring the server's cache headers. APK 33.8 MB
(0.28.1: 33.6 MB).

## 0.28.1, 2026-09-21 · `versionCode 48`
The Listen pill now rises from the bottom of the screen together with the
Prev / Next bar, and leaves with it, instead of popping in on its own above a
bar that slid. While narration is playing and the bars are hidden, the player
stays on its own and settles down to the edge; bringing the bars back slides
the Prev / Next bar in underneath it.

## 0.28.0, 2026-09-21 · `versionCode 47`
Ratings. Tap a star under the author on the book screen to rate it 1 to 5;
tap the current rating again to clear it, same as on the web. The rating is
saved to the server and shows as small stars on the library card.

## 0.27.0, 2026-09-21 · `versionCode 46`
Download volumes from the book screen: ⋮ → Download lists every volume, each
saved as an EPUB, plus all of them as one zip when there's more than one. Files
go through the system download manager, so they get a notification, keep going
after the app is left, and land in Downloads.

## 0.26.2, 2026-09-20 · `versionCode 45`
Covers are requested at the size they're drawn (`?w=800`), instead of at
whatever resolution the source site published. 80% fewer bytes over mobile data
(2 905 376 B → 581 870 B across the 9-cover test library) with no visible
softening. Needs the matching server release; an older server ignores the
parameter and serves the original.

## 0.26.1, 2026-09-20 · `versionCode 44`
The OkHttp logging interceptor no longer runs in release builds. It had been
formatting a logcat line for every request *and* every image Coil pulled through
the shared client, and putting the user's library activity into the device log.

## 0.26.0, 2026-09-20 · `versionCode 43`
R8 and resource shrinking enabled. dex drops from 46 774 612 B across three
files to **3 474 384 B in one** (−93%), `resources.arsc` 420 220 → 173 508 B,
`res/` 93 → 48 entries. Cold start 236 → 211 ms, idle memory 46.4 → 36.0 MB.
Keep rules cover the four things R8 can't see: sherpa-onnx's JNI classes,
kotlinx-serialization's generated serializers, Retrofit's reflective proxy, and
`@JavascriptInterface` members on the offscreen WebViews.

## 0.25.0, 2026-09-20 · `versionCode 42`
Per-ABI APKs. The release used to package arm64-v8a *and* x86_64 together;
x86_64 exists only for the emulator, yet its 33.9 MiB of sherpa-onnx and ONNX
Runtime libs were 42% of every APK a phone downloaded and could never run.
Phone APK **80 391 724 → 44 824 901 B**.

## 0.24.0, 2026-09-20 · `versionCode 41`
Reader immersive mode: the toolbars and the Android status/nav bars hide while
reading, leaving only the text. A tap toggles them, scrolling hides them, and
they start visible when a chapter opens.

## 0.23.1, 2026-08-24 · `versionCode 40`
NovelUpdates scrapes carry the title and author across, so books stop importing
as "Unknown Author". NU scrapes start from a translator's chapter page, whose OG
tags rarely include the author: it lives on the novel page, which the app
already reads.

## 0.23.0, 2026-08-24 · `versionCode 39`
Paste a NovelUpdates series link into the normal URL field and scrape it. The
app reads the translation groups in an offscreen WebView using the saved NU
login, shows the group chooser, and scrapes the chosen group from chapter 1: no separate browser screen needed.

## 0.22.1, 2026-08-24 · `versionCode 38`
Two NovelUpdates fixes: it had been scraping only the last chapter (it resolved
a *recent* chapter from NU's landing table and enumerated forward from there),
and it showed a wrong chapter count instead of the latest chapter.

## 0.22.0, 2026-08-24 · `versionCode 37`
Add novels from NovelUpdates: an in-app browser and a translation-group chooser.
NU sits behind a Cloudflare challenge that only reveals group and chapter links
when logged in, so the backend can't fetch it: a real in-app WebView passes the
challenge and NU is logged into on its own page.

## 0.21.0, 2026-08-24 · `versionCode 36`
WebView render fallback for JS-only and Cloudflare-gated pages: when the static
HTML is unusable the page is rendered in the phone's WebView and extracted from
the post-JS DOM, feeding the normal pipeline.

## 0.20.0, 2026-08-20 · `versionCode 35`
Due books auto-update when the app comes to the foreground or on sign-in.
Cloudflare-gated sources 403 from the server, so the background updater can
never fetch them; this queues the work while the phone relay is up.

## 0.19.0, 2026-08-18 · `versionCode 34`
In-chapter illustrations appear in the reader. The native reader had flattened
chapter HTML to plain text, dropping every `<img>`, so imported-EPUB
illustrations never showed.

## 0.18.0, 2026-08-15 · `versionCode 33`
Three library-management features: delete a novel (overflow menu → confirm),
check for new chapters, and export reading progress.

## 0.17.0, 2026-08-14 · `versionCode 32`
**Scrape through the phone's IP.** Cloudflare 403s the server's datacentre IP
for some sources (novelfire/novelphoenix, freewebnovel, novelhall) but not a
residential or mobile one, so each scrape's raw HTTP fetch is routed through the
phone over a WebSocket relay.

## 0.16.2, 2026-08-13 · `versionCode 31`
The spoken sentence is centred in the reader. The follow-scroll had used a
proportional estimate that landed it near the bottom, where the floating Listen
pill covered it; it now uses the real on-screen position from the text layout.

## 0.16.1, 2026-08-13 · `versionCode 30`
Narration stops on leaving a chapter, but survives backgrounding, screen-off
and rotation.

## 0.16.0, 2026-08-13 · `versionCode 29`
Piper gains a curated voice set, each a ~65 MB download-on-demand: US English
(Amy, Ryan, Lessac, Joe) and British English (Alan, Cori, Alba, Northern). It
had been a single voice.

## 0.15.0, 2026-08-13 · `versionCode 28`
Piper (VITS) added as a second on-device engine alongside Kokoro. Kokoro-82M
runs at about 1× real time on this CPU, its ceiling, and RTF logging confirmed
no thread or provider combination reaches 2×. A lighter Piper model does, via
the same sherpa-onnx `OfflineTts`.

## 0.14.9, 2026-08-13 · `versionCode 27`
Fix the Kokoro speed regression. On-device RTF logging showed the previous
"speedup" (XNNPACK + 6 threads) was actually *slower*, RTF ~1.05–1.25, slower
than real time, which caused the buffering, versus ~0.79–0.95 on plain CPU.
Reverted to the plain CPU execution provider with threads capped at 4.

## 0.14.8, 2026-08-13 · `versionCode 26`
Log Kokoro's real-time factor per chunk, to tune speed on-device by measurement
rather than by guessing (the Dimensity 8400's cores span 3.25/3.0/2.1 GHz, so
more threads can be slower).

## 0.14.7, 2026-08-13 · `versionCode 25`
Kokoro audio quality: switch off `CONTENT_TYPE_SPEECH`, which some OEMs route
through a bandlimited voice-processing path (the "old radio" tinniness), and
de-pop with clamping plus edge fades.

## 0.14.6, 2026-08-13 · `versionCode 24`
Speed up Kokoro and remove playback buffering. Underruns came from a shallow
pipeline: a 2-sentence prefetch and a ~0.5 s AudioTrack buffer, with only four
inference threads.

## 0.14.5, 2026-08-13 · `versionCode 23`
Switch Kokoro to the multilingual v1.0 model. The v1.1-zh model shipped in
0.14.0 contains only English and Chinese: three English voices, all female,
and 100 Mandarin, which is the wrong model for a reader who wants accents.

## 0.14.4, 2026-08-13 · `versionCode 22`
Sort Kokoro voices into gendered sections with counts. The single "Chinese
(Mandarin)" section listed 55 female then 45 male, so the numbering jumped back
at the boundary and looked unsorted.

## 0.14.3, 2026-08-13 · `versionCode 21`
Name the Kokoro voices and group them by nationality, instead of "Voice #n", all 103 speakers in the exact `voices.bin` order.

## 0.14.2, 2026-08-13 · `versionCode 20`
Reader and TTS fixes: collapsible per-volume accordions in the book TOC and a
new reader "Chapters" bottom sheet, plus progress-marking, scroll and
TTS-start fixes.

## 0.14.1, 2026-08-13 · `versionCode 19`
Fix the Kokoro model download failing with ENOENT on a fresh install, `cacheDir` can be absent right after install, so the temp tar stream failed
before any bytes were written.

## 0.14.0, 2026-08-13 · `versionCode 18`
**On-device neural TTS via sherpa-onnx (Kokoro).** Streams the ~150 MB model,
extracts it with a zip-slip guard, and swaps it into place atomically so a
killed download can't leave half a model. Synthesis is single-flight (ONNX
Runtime isn't thread-safe) and release can't free the engine mid-generate, that
had been a use-after-free crash. Falls back to the device TTS if the model isn't
ready. Also fixes a packaging bug: an unanchored `data/` ignore rule had been
matching the app's `com/novelscraper/app/data/` package, so three source files
were never committed and a fresh clone wouldn't build.

## 0.13.1, 2026-08-13 · `versionCode 17`
The voice picker lists every installed voice. It had filtered to the device's
current language, so other-language voices never appeared.

## 0.13.0, 2026-08-13 · `versionCode 16`
Sentence highlighting, and tap a sentence to start narration there. A shared
splitter is used by both the reader and the TTS service so the indices line up
with what's spoken. Trade-off: the reader renders plain text (paragraph breaks
kept) rather than rich HTML spans, which is what lets the highlight align.

## 0.12.0, 2026-08-13 · `versionCode 15`
The reader's "Listen" pill, matching the web player: rewind / play-pause /
forward, an elapsed-and-estimated-total readout, a seek slider that jumps to a
sentence, and an expandable panel with speech rate and voice.

## 0.11.2, 2026-08-12 · `versionCode 14`
Smoother tab slide. Two causes: debug builds run Compose and ART unoptimized, so
a signed release build type was added; and some screens started network loads
during composition, landing the recompose mid-animation, so those are deferred
past the slide.

## 0.11.1, 2026-08-12 · `versionCode 13`
Consistent headers across tabs, direction-aware horizontal slide transitions,
and a fix for books taking up to 10 s to open, book and chapters are now
fetched in parallel and rendered immediately, rather than blocking on
`/progress` and its server-side word-count backfill.

## 0.11.0, 2026-08-12 · `versionCode 12`
EPUB import on the New Scrape screen, streamed straight from the content
resolver so large files aren't held in memory. This is also the workaround for
Cloudflare-gated sources: scrape on a device with a residential IP, export, and
import here.

## 0.10.0, 2026-08-12 · `versionCode 11`
Assign books to collections from the book page, plus a book-detail and reader
restyle (larger cover, serif body at a ~620 dp reading measure).

## 0.9.0, 2026-08-12 · `versionCode 10`
Real New Scrape, Progress and Stats screens, replacing the placeholders, URL
submission with unsupported-source and duplicate handling, live job polling with
cancel, and reading statistics.

## 0.8.0, 2026-08-12 · `versionCode 9`
Library collection tabs and drag-to-reorder. Long-press a cover to drag;
long-press a tab to rename or delete it (novels are kept).

## 0.7.1, 2026-08-12 · `versionCode 8`
Fix covers never loading on-device. The default base URL had no trailing slash
and only `setBaseUrl` normalised it, so hand-built cover URLs became
`https://novelscraper.comapi/books/…` and failed DNS. Retrofit had hidden the
bug because `HttpUrl` adds the root slash itself.

## 0.7.0, 2026-08-12 · `versionCode 7`
Editorial redesign to match the web app: the real fonts (Playfair Display,
Source Serif 4, JetBrains Mono), all four palettes, a floating pill bottom nav
whose selected item expands, and new Settings and Register screens.

## 0.6.0, 2026-08-12 · `versionCode 6`
**Background and lock-screen narration.** A foreground service narrates with the
device TTS engine, so playback continues with the screen off, which the web
app's Web Speech engine can't do. MediaSession gives lock-screen transport and
headset buttons; audio focus and becoming-noisy are handled.

## 0.5.0, 2026-08-12 · `versionCode 5`
The reader: chapter rendering, prev/next gated on availability, persisted font
size, and progress sync.

## 0.4.0, 2026-08-12 · `versionCode 4`
Book detail and chapter list, cover header, read count and percentage, a
Continue button that resumes from the last position, and a volume-grouped
chapter list with read checkmarks.

## 0.3.0, 2026-08-12 · `versionCode 3`
The library grid. Covers load through the same OkHttp client as the API, so they
carry the session cookie; books without one get an initials placeholder.

## 0.2.0, 2026-08-12 · `versionCode 2`
Networking and sign-in: typed REST client, a persistent cookie jar so the
session survives restarts, a configurable server address, and session resume.

## 0.1.0, 2026-08-12 · `versionCode 1`
Scaffold: a native Kotlin + Jetpack Compose app (not a WebView wrapper),
compileSdk 34 / minSdk 26, with the JDK pinned to Temurin 21.
