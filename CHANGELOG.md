# Changelog

### Version 2.10.10

* Minor bug fixes
* Restore ability to call out via JMP and other services (Playstore version)

### Version 2.10.9

* Ask for Bluetooth permissions when making A/V calls (You can reject this if you don’t use Bluetooth headsets)
* Fix bug when calling Movim

### Version 2.10.8

* Fix wrong avatar being shown for group chats

### Version 2.10.7

* always ask for battery optimizations opt-out
* set local only flag on 'x connected accounts' notifications
* Minor bug fixes

### Version 2.10.6

* Minor bug fixes

### Version 2.10.5

* Security: Stop downloading files that exceed advertised file size
* Security: Limit POSH files to 10K

### Version 2.10.4

* Fix interaction with Google Maps Share Location Plugin
* Remove footnote with regards to server fee

### Version 2.10.3

* Store files in location appropriate for Android 11
* Attempt to reconnect call after network switch
* Show caller JID and account JID in incoming call screen

### Version 2.10.2

* Fix crash when rendering some quotes
* Fix crash in welcome screen

### Version 2.10.1

* Fix issue with some videos not being compressed
* Fix rare crash when opening notification

### Version 2.10.0

* Show black bars when remote video does not match aspect ratio of screen
* Improve search performance
* Add setting to prevent screenshots

### Version 2.9.13

* minor A/V improvements

### Version 2.9.12

* Always verify domain name. No user overwrite
* Support roster pre authentication

### Version 2.9.11

* Fixed 'No Connectivity' issues on Android 7.1

### Version 2.9.10
* fix HTTP up/download for users that don’t trust system CAs

### Version 2.9.9

* Various bug fixes around Tor support
* Improve call compatibility with Dino

### Version 2.9.8

* Verify A/V calls with preexisting OMEMO sessions
* Improve compatibility with non libwebrtc WebRTC implementations

### Version 2.9.7

* Ability to select incoming call ringtone
* Fix OpenPGP key id discovery for OpenKeychain 5.6+
* Properly verify punycode TLS certificates
* Improve stability of RTP session establishment (calling)

### Version 2.9.6

* Show call button for offline contacts if they previously announced support
* Back button no longer ends call when call is connected
* bug fixes

### Version 2.9.5

* Quicksy: Automatically receive verification SMS

### Version 2.9.4
* minor stability improvements for A/V calls
* Conversations releases from here on forward require Android 5

### Version 2.9.3

* Fixed connectivity issues when different accounts used different SCRAM mechanisms
* Add support for SCRAM-SHA-512
* Allow P2P (Jingle) file transfer with self contact

### Version 2.9.2

* Offer Easy Invite generation on supporting servers
* Display GIFs send from Movim
* store avatars in cache

### Version 2.9.1

* fixed search on Android <= 5
* optimize memory consumption

### Version 2.9.0

* Search individual conversations
* Notify user if message delivery fails
* Remember display names (nicks) from Quicksy users across restarts
* Add button to start Orbot (Tor) from notification if necessary

### Version 2.8.10

* Handle GPX files
* Improve performance for backup restore
* bug fixes

### Version 2.8.9

* add 'Return to chat' to audio call screen
* Improve keyboard shortcuts
* bug fixes

### Version 2.8.8

* Fixed notifications not showing up under certain conditions
* Fixed compatibility issues and crashes related to A/V calls

### Version 2.8.7

* Show help button if A/V call fails
* Fixed some annoying crashes
* Fixed Jingle connections (file transfer + calls) with bare JIDs

### Version 2.8.6

* Offer to record voice message when callee is busy

### Version 2.8.5

* Reduce echo during calls on some devices
* Fix login when passwords contains special characters
* Play dial and busy tones on speaker during video calls

### Version 2.8.4

* Rework Login with certificate UI
* Add ability to pin chats on top (add to favorites)

### Version 2.8.3

* Move call icon to the left in order to keep other toolbar icons in a consistent place
* Show call duration during audio calls
* Tie breaking for A/V calls (the same two people calling each other at the same time)

### Version 2.8.2

* Add button to switch camera during video call
* Fixed voice calls on tablets

### Version 2.8.1

* Audible feedback (dialing, call started, call ended) for voice calls.
* Fixed issue with retrying failed video call

### Version 2.8.0

* Audio/Video calls (Requires server support in form of STUN and TURN servers discoverable via XEP-0215)


### Version 2.7.1

* Fix avatar selection on some Android 10 devices
* Fix file transfer for larger files

### Version 2.7.0

* Provide PDF preview on Android 5+
* Use 12 byte IVs for OMEMO

### Version 2.6.4

* Support automatic theme switching on Android 10

### Version 2.6.3

* Support for ?register and ?register;preauth XMPP uri parameters

### Version 2.6.2
* let users set their own nick name
* resume download of OMEMO encrypted files
* Channels now use '#' as symbol in avatar
* Quicksy uses 'always' as OMEMO encryption default (hides lock icon)

### Version 2.6.1
* fixes for Jingle IBB file transfer
* fixes for repeated corrections filling up the database
* switched to Last Message Correction v1.1

### Version 2.6.0
* Introduce expert setting to perform channel discovery on local server instead of [search.jabber.network](https://search.jabber.network)
* Enable delivery check marks by default and remove setting
* Enable ‘Send button indicates status’ by default and remove setting
* Move Backup and Foreground Service settings to main screen

### Version 2.5.12
* Jingle file transfer fixes
* Fixed OMEMO self healing (after backup restore) on servers w/o MAM

### Version 2.5.11
* Fixed crash on Android <5.0

### Version 2.5.10
* Fixed crash on Xiaomi devices running Android 8.0 + 8.1

### Version 2.5.9
* fixed minor security issues
* Share XMPP uri from channel search by long pressing a result

### Version 2.5.8
* fixed connection issues over Tor
* P2P file transfer (Jingle) now offers direct candidates
* Support XEP-0396: Jingle Encrypted Transports - OMEMO

### Version 2.5.7
* fixed crash when scanning QR codes on Android 6 and lower
* when sharing a message from and to Conversations insert it as quote

### Version 2.5.6
* fixes for Jingle file transfer
* fixed some rare crashes

### Version 2.5.5
* allow backups to be restored from anywhere
* bug fixes

### Version 2.5.4
* stability improvements for group chats and channels

### Version 2.5.3
* bug fixes for peer to peer file transfer (Jingle)
* fixed server info for unlimited/unknown max file size

### Version 2.5.2
* bug fixes

### Version 2.5.1
* minor bug fixes
* Set own OMEMO devices to inactive after not seeing them for 14 days. (was 7 days)

### Version 2.5.0
* Added channel search via search.jabbercat.org
* Reworked onboarding screens
* Warn when trying to enter domain address or channel address in Add Contact dialog

### Version 2.4.3
* Fixed display of private messages sent from another client
* Fixed backup creation on long time installations

### Version 2.4.2
* Fix image preview on older Android version

### Version 2.4.1
* Fixed crash in message view

### Version 2.4.0
* New Backup / Restore feature
* Clearly distinguish between (private) group chats and (public) channels
* Redesigned participants view for group chats and channels
* Redesigned create new contact/group chat/channel flow in Start Conversation screen


### Version 2.3.12
* Fixed rare crash on start up
* Fixed avatar not being refreshed in group chats

### Version 2.3.11
* Support for Android 9 'message style' notifications
* OMEMO stability improvements
* Added ability to destroy group chats
* Do not show deleted files in media browser
* Added 'Keep Original' as video quality choice

### Version 2.3.10
* lower minimum required Android version to 4.1
* Synchronize group chat join/leaves across multiple clients
* Fixed sending PGP encrypted messages from quick reply

### Version 2.3.9
* OMEMO stability improvements
* Context menu when long pressing avatar in 1:1 chat

### Version 2.3.8
* make PEP avatars public to play nice with Prosody 0.11
* Fixed re-sending failed files in group chats

### Version 2.3.7
* long press on 'allow' or 'add back' snackbar to bring up 'reject'
* bug fixes for Android 9

### Version 2.3.6
* Improved handling of bookmark nicks
* Show send PM menu entry in anonymous MUCs

### Version 2.3.5
* Fixed group chat mentions when nick ends in . (dot)
* Fixed Conversations not asking for permissions after direct share
* Fixed CVE-2018-18467

### Version 2.3.4
* Fixed sending OMEMO files to ChatSecure

### Version 2.3.3
* Fixed connection issues with user@ip type JIDs

### Version 2.3.2
* Fixed OMEMO on Android 5.1 & 6.0
* Added setting for video quality
* bug fixes

### Version 2.3.1
* Stronger compression for video files
* Use SNI on STARTTLS to fix gtalk
* Fix Quiet Hours on Android 8+
* Use Consistent Color Generation (XEP-0392)

### Version 2.3.0
* Preview and ask for confirmation before sending media files
* View per conversation media files in contact and conference details screens
* Enable foreground service by default for Android 8 (notification can be disabled by long pressing it)
* Audio player: disable screen and switch to ear piece
* Support TLSv1.3 (ejabberd ≤ 18.06 is incompatible with openssl 1.1.1 - Update ejabberd or downgrade openssl if you get ›Stream opening error‹)


### Version 2.2.9
* Store bookmarks in PEP if server has ability to convert to old bookmarks
* Show Jabber IDs from address book in Start Conversation screen

### Version 2.2.8
* fixed regression that broke XMPP uris

### Version 2.2.7
* stability improvements

### Version 2.2.6
* support old MAM version to work with Prosody

### Version 2.2.5
* Persist MUC avatar across restarts / show in bookmarks
* Offer Paste as quote for HTML content

### Version 2.2.4
* Use group chat name as primary identifier
* Show group name and subject in group chat details
* Upload group chat avatar on compatible servers

### Version 2.2.3
* Introduce Expert Setting to enable direct search
* Introduce Paste As Quote on Android 6+
* Fixed issues with HTTP Upload

### Version 2.2.2
* Fixed connection problems with TLS1.3 servers
* Attempt to delete broken bundles from PEP
* Use FCM instead of GCM

### Version 2.2.1
* improved recording quality
* load map tiles over Tor if enabled 

### Version 2.2.0
* Integrate Voice Recorder
* Integrate Share Location
* Added ability to search messages

### Version 2.1.4
* bug fixes

### Version 2.1.3
* Do not process stanzas with invalid JIDs

### Version 2.1.2
* Fixed avatars not being displayed on new installs

### Version 2.1.1
* Improved start up performance
* bug fixes

### Version 2.1.0
* Added configurable font size
* Added global OMEMO preference
* Added scroll to bottom button
* Only mark visible messages as read


### Version 2.0.0
* OMEMO by default for everything but public group chats
* Integrate QR code scanner (requires camera permission)
* Removed support for OTR
* Removed support for customizable resources
* Removed slide out panel for conversation overview
* Add ability to change status message
* Highlight irregular unicode code blocks in Jabber IDs
* Conversations now requires Android 4.4+

### Version 1.23.8
* bug fixes

### Version 1.23.7
* Improved MAM support + bug fixes

### Version 1.23.6
* Fixed crash on receiving invalid HTTP slot response

### Version 1.23.5
* improved self chat

### Version 1.23.4
* keep screen on while playing audio
* send delivery receipts after MAM catch-up
* reduce number of wake locks

### Version 1.23.3
* Fixed OMEMO device list not being announced

### Version 1.23.2
* Removed NFC support
* upload Avatars as JPEG
* reduce APK size

### Version 1.23.1
* Show icon instead of image preview in conversation overview
* fixed loop when trying to decrypt with YubiKey

### Version 1.23.0
* Support for read markers in private, non-anonymous group chats

### Version 1.22.1
* Disable swipe to left to end conversation
* Fixed 'No permission to access …' when opening files shared from the SD card
* Always open URLs in new tab

### Version 1.22.0
* Text markup *bold*, _italic_,`monospace` and ~strikethrough~
* Use same emoji style on all Android versions
* Display emojis slightly larger within continuous text

### Version 1.21.0
* Inline player for audio messages
* Stronger compression for long videos
* Long press the 'add back' button to show block menu

### Version 1.20.1
* fixed OTR encrypted file transfer

### Version 1.20.0
* presence subscription no longer required for OMEMO on compatible servers
* display emoji-only messages slightly larger

### Version 1.19.5
* fixed connection loop on Android <4.4

### Version 1.19.4
* work around for OpensFire’s self signed certs
* use VPN’s DNS servers first

### Version 1.19.3
* Do not create foreground service when all accounts are disabled
* bug fixes

### Version 1.19.2
* bug fixes

### Version 1.19.1
* Made DNSSEC hostname validation opt-in

### Version 1.19.0
* Added 'App Shortcuts' to quickly access frequent contacts
* Use DNSSEC to verify hostname instead of domain in certificate
* Setting to enable Heads-up notifications
* Added date separators in message view

### Version 1.18.5
* colorize send button only after history is caught up
* improved MAM catchup strategy

### Version 1.18.4
* fixed UI freezes during connection timeout
* fixed notification sound playing twice
* fixed conversations being marked as read
* removed 'copy text' in favor of 'select text' and 'share with'

### Version 1.18.3
* limited GPG encryption for MUC offline members

### Version 1.18.2
* added support for Android Auto
* fixed HTTP Download over Tor
* work around for nimbuzz.com MUCs

### Version 1.18.1
* bug fixes

### Version 1.18.0
* Conversations <1.16.0 will be unable to receive OMEMO encrypted messages
* OMEMO: put auth tag into key (verify auth tag as well)
* offer to block entire domain in message from stranger snackbar 
* treat URL as file if URL is in oob or contains key

### Version 1.17.1
* Switch Aztec to QR for faster scans
* Fixed unread counter for image messages

### Version 1.17.0
* Do not notify for messages from strangers by default
* Blocking a JID closes the corresponding conversation
* Show message sender in conversation overview
* Show unread counter for every conversation
* Send typing notifications in private, non-anonymous MUCs
* Support for the latest MAM namespace
* Icons for attach menu

### Version 1.16.2
* change mam catchup strategy. support mam:1
* bug fixes

### Version 1.16.1
* UI performance fixes
* bug fixes

### Version 1.16.0
* configurable client side message retention period
* compress videos before sending them

### Version 1.15.5
* show nick as bold text when mentioned in conference
* bug fixes

### Version 1.15.4
* bug fixes

### Version 1.15.3
* show offline contacts in MUC as grayed-out
* don't transcode gifs. add overlay indication to gifs
* bug fixes

### Version 1.15.2
* bug fixes

### Version 1.15.1
* support for POSH (RFC7711)
* support for quoting messages (via select text)
* verified messages show shield icon. unverified messages show lock

### Version 1.15.0
* New [Blind Trust Before Verification](https://gultsch.de/trust.html) mode
* Easily share Barcode and XMPP uri from Account details
* Automatically deactivate own devices after 7 day of inactivity
* Improvements fo doze/push mode
* bug fixes

### Version 1.14.9
* warn in account details when data saver is enabled
* automatically enable foreground service after detecting frequent restarts
* bug fixes

### Version 1.14.8
* bug fixes

### Version 1.14.7
* error message accessible via context menu for failed messages
* don't include pgp signature in anonymous mucs
* bug fixes

### Version 1.14.6
* make error notification dismissible
* bug fixes


### Version 1.14.5
* expert setting to delete OMEMO identities
* bug fixes

### Version 1.14.4
* bug fixes

### Version 1.14.3
* XEP-0377: Spam Reporting
* fix rare start up crashes

### Version 1.14.2
* support ANONYMOUS SASL
* bug fixes

### Version 1.14.1
* Press lock icon to see why OMEMO is deactivated
* bug fixes

### Version 1.14.0
* Improvements for N
* Quick Reply to Notifications on N
* Don't download avatars and files when data saver is on
* bug fixes

### Version 1.13.9
* bug fixes

### Version 1.13.8
* show identities instead of resources in selection dialog
* allow TLS direct connect when port is set to 5223
* bug fixes

### Version 1.13.7
* bug fixes

### Version 1.13.6
* thumbnails for videos
* bug fixes

### Version 1.13.5
* bug fixes

### Version 1.13.4
* support jingle ft:4
* show contact as DND if one resource is
* bug fixes

### Version 1.13.3
* bug fixes

### Version 1.13.2
* new PGP decryption logic
* bug fixes

### Version 1.13.1
* changed some colors in dark theme
* fixed fall-back message for OMEMO

### Version 1.13.0
* configurable dark theme
* opt-in to share Last User Interaction

### Version 1.12.9
* make grace period configurable

### Version 1.12.8
* more bug fixes :-(

### Version 1.12.7
* bug fixes

### Version 1.12.6
* bug fixes

### Version 1.12.5
* new create conference dialog
* show first unread message on top
* show geo uri as links
* circumvent long message DOS

### Version 1.12.4
* show offline members in conference (needs server support)
* various bug fixes

### Version 1.12.3
* make omemo default when all resources support it
* show presence of other resources as template
* start typing in StartConversationsActivity to search
* various bug fixes and improvements

### Version 1.12.2
* fixed pgp presence signing

### Version 1.12.1
* small bug fixes

### Version 1.12.0
* new welcome screen that makes it easier to register account
* expert setting to modify presence

### Version 1.11.7
* Share xmpp uri from conference details
* add setting to allow quick sharing
* various bug fixes

### Version 1.11.6
* added preference to disable notification light
* various bug fixes

### Version 1.11.5
* check file ownership to not accidentally share private files

### Version 1.11.4
* fixed a bug where contacts are shown as offline
* improved broken PEP detection

### Version 1.11.3
* check maximum file size when using HTTP Upload
* properly calculate caps hash

### Version 1.11.2
* only add image files to media scanner
* allow to delete files
* various bug fixes

### Version 1.11.1
* fixed some bugs when sharing files with Conversations

### Version 1.11.0
* OMEMO encrypted conferences

### Version 1.10.1
* made message correction opt-in
* various bug fixes

### Version 1.10.0
* Support for XEP-0357: Push Notifications
* Support for XEP-0308: Last Message Correction
* introduced build flavors to make dependence on play-services optional

### Version 1.9.4
* prevent cleared Conversations from reloading history with MAM
* various MAM fixes

### Version 1.9.3
* expert setting that enables host and port configuration
* expert setting opt-out of bookmark autojoin handling
* offer to rejoin a conference after server sent unavailable
* internal rewrites

### Version 1.9.2
* prevent startup crash on Sailfish OS
* minor bug fixes

### Version 1.9.1
* minor bug fixes incl. a workaround for nimbuzz.com

### Version 1.9.0
* Per conference notification settings
* Let user decide whether to compress pictures
* Support for XEP-0368
* Ask user to exclude Conversations from battery optimizations

### Version 1.8.4
* prompt to trust own OMEMO devices
* fixed rotation issues in avatar publication
* invite non-contact JIDs to conferences

### Version 1.8.3
* brought text selection back

### Version 1.8.2
* fixed stuck at 'connecting...' bug
* make message box behave correctly with multiple links

### Version 1.8.1
* enabled direct share on Android 6.0
* ask for permissions on Android 6.0
* notify on MAM catchup messages
* bug fixes

### Version 1.8.0
* TOR/ORBOT support in advanced settings
* show vcard avatars of participants in a conference

### Version 1.7.3
* fixed PGP encrypted file transfer
* fixed repeating messages in slack conferences

### Version 1.7.2
* decode PGP messages in background

### Version 1.7.1
* performance improvements when opening a conversation

### Version 1.7.0
* CAPTCHA support
* SASL EXTERNAL (client certificates)
* fetching MUC history via MAM
* redownload deleted files from HTTP hosts
* Expert setting to automatically set presence
* bug fixes

### Version 1.6.11
* tab completion for MUC nicks
* history export
* bug fixes

### Version 1.6.10
* fixed facebook login
* fixed bug with ejabberd mam
* use official HTTP File Upload namespace

### Version 1.6.9
* basic keyboard support

### Version 1.6.8
* reworked 'enter is send' setting
* reworked DNS server discovery on lolipop devices
* various bug fixes

### Version 1.6.7
* bug fixes

### Version 1.6.6
* best 1.6 release yet

### Version 1.6.5
* more OMEMO fixes

### Version 1.6.4
* setting to enable white chat bubbles
* limit OMEMO key publish attempts to work around broken PEP
* various bug fixes

### Version 1.6.3
* bug fixes

### Version 1.6.2
* fixed issues with connection time out when server does not support ping

### Version 1.6.1
* fixed crashes

### Version 1.6.0
* new multi-end-to-multi-end encryption method
* redesigned chat bubbles
* show unexpected encryption changes as red chat bubbles
* always notify in private/non-anonymous conferences

### Version 1.5.1
* fixed rare crashes
* improved otr support

### Version 1.5.0
* upload files to HTTP host and share them in MUCs. requires new [HttpUploadComponent](https://github.com/siacs/HttpUploadComponent) on server side

### Version 1.4.5
* fixes to message parser to not display some ejabberd muc status messages

### Version 1.4.4
* added unread count badges on supported devices
* rewrote message parser

### Version 1.4.0
* send button turns into quick action button to offer faster access to take photo, send location or record audio
* visually separate merged messages
* faster reconnects of failed accounts after network switches 
* r/o vcard avatars for contacts
* various bug fixes

### Version 1.3.0
* swipe conversations to end them
* quickly enable / disable account via slider
* share multiple images at once
* expert option to distrust system CAs
* mlink compatibility
* bug fixes

### Version 1.2.0
* Send current location. (requires [plugin](https://play.google.com/store/apps/details?id=eu.siacs.conversations.sharelocation))
* Invite multiple contacts at once
* performance improvements
* bug fixes

### Version 1.1.0
* Typing notifications (must be turned on in settings)
* Various UI performance improvements
* bug fixes

### Version 1.0.4
* load avatars asynchronously on start up
* support for XEP-0092: Software Version

### Version 1.0.3
* load messages asynchronously on start up
* bug fixes

### Version 1.0.2
* skipped

### Version 1.0.1
* accept more ciphers

### Version 1.0
* MUC controls (Affiliation changes)
* Added download button to notification
* Added check box to hide offline contacts
* Use Material theme and icons on Android L
* Improved security
* bug fixes + code clean up

### Version 0.10
* Support for Message Archive Management
* Dynamically load message history
* Ability to block contacts
* New UI to verify fingerprints
* Ability to change password on server
* removed stream compression
* quiet hours
* fixed connection issues on ipv6 servers

### Version 0.9.3
* bug fixes

### Version 0.9.2
* more bug fixes

### Version 0.9.1
* bug fixes including some that caused Conversations to crash on start

### Version 0.9
* arbitrary file transfer
* more options to verify OTR (SMP, QR Codes, NFC)
* ability to create instant conferences
* r/o dynamic tags (presence and roster groups)
* optional foreground service (expert option)
* added SCRAM-SHA1 login method
* bug fixes

### Version 0.8.4
* bug fixes

### Version 0.8.3
* increased UI performance
* fixed rotation bugs

### Version 0.8.2
* Share contacts via QR codes or NFC
* Slightly improved UI
* minor bug fixes

### Version 0.8.1
* minor bug fixes

### Version 0.8
* Download HTTP images
* Show avatars in MUC tiles
* Disabled SSLv3
* Performance improvements
* bug fixes

### Version 0.7.3
* revised tablet ui
* internal rewrites
* bug fixes

### Version 0.7.2
* show full timestamp in messages
* brought back option to use JID to identify conferences
* optionally request delivery receipts (expert option)
* more languages
* bug fixes

### Version 0.7.1
* Optionally use send button as status indicator

### Version 0.7
* Ability to disable notifications for single conversations
* Merge messages in chat bubbles
* Fixes for OpenPGP and OTR (please republish your public key)
* Improved reliability on sending messages
* Join password protected Conferences
* Configurable font size
* Expert options for encryption

### Version 0.6
* Support for server side avatars
* save images in gallery
* show contact name and picture in non-anonymous conferences
* reworked account creation
* various bug fixes

### Version 0.5.2
* minor bug fixes

### Version 0.5.1
* couple of small bug fixes that have been missed in 0.5
* complete translations for Swedish, Dutch, German, Spanish, French, Russian

### Version 0.5
* UI overhaul
* MUC / Conference bookmarks
* A lot of bug fixes

### Version 0.4
* OTR file encryption
* keep OTR messages and files on device until both parties or online at the same time
* XEP-0333. Mark whether the other party has read your messages
* Delayed messages are now tagged properly
* Share images from the Gallery
* Infinite history scrolling
* Mark the last used presence in presence selection dialog

### Version 0.3
* Mostly bug fixes and internal rewrites
* Touch contact picture in conference to highlight
* Long press on received image to share
* made OTR more reliable
* improved issues with occasional message lost
* experimental conference encryption. (see FAQ)

### Version 0.2.3
* regression fix with receiving encrypted images

### Version 0.2.2
* Ability to take photos directly
* Improved openPGP offline handling
* Various bug fixes
* Updated Translations

### Version 0.2.1
* Various bug fixes
* Updated Translations

### Version 0.2
* Image file transfer
* Better integration with OpenKeychain (PGP encryption)
* Nicer conversation tiles for conferences
* Ability to clear conversation history
* A lot of bug fixes and code clean up

### Version 0.1.3
* Switched to minidns library to resolve SRV records
* Faster DNS in some cases
* Enabled stream compression
* Added permanent notification when an account fails to connect
* Various bug fixes involving message notifications
* Added support for DIGEST-MD5 auth

### Version 0.1.2
* Various bug fixes relating to conferences
* Further DNS lookup improvements

### Version 0.1.1
* Fixed the 'server not found' bug

### Version 0.1
* Initial release
