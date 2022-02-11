<h1 align="center">Conversations</h1>

<p align="center">Conversations: the very last word in instant messaging</p>

<p align="center">
    <a href="https://conversations.im/j/conversations@conference.siacs.eu">
        <img src="https://inverse.chat/badge.svg?room=conversations@conference.siacs.eu"
             alt="chat on our conference room">
    </a>
    <a href="https://travis-ci.org/inputmice/Conversations">
        <img src="https://travis-ci.org/inputmice/Conversations.svg?branch=master"
             alt="build status">
    </a>
</p>

<p align="center">
    <a href="https://play.google.com/store/apps/details?id=eu.siacs.conversations&amp;referrer=utm_source%3Dgithub">
       <img src="https://conversations.im/images/en-play-badge.png" alt="Google Play">
    </a>
</p>

![screenshots](https://raw.githubusercontent.com/inputmice/Conversations/master/screenshots.png)

## Design principles

* Be as beautiful and easy to use as possible without sacrificing security or
  privacy
* Rely on existing, well established protocols (XMPP)
* Do not require a Google Account or specifically Google Cloud Messaging (GCM)

## Features

* End-to-end encryption with [OMEMO](http://conversations.im/omemo/) or [OpenPGP](http://openpgp.org/about/)
* Send and receive images as well as other kind of files
* [Encrypted audio and video calls (DTLS-SRTP)](https://help.conversations.im)
* Share your location
* Send voice messages
* Indication when your contact has read your message
* Intuitive UI that follows Android Design guidelines
* Pictures / Avatars for your Contacts
* Synchronizes with desktop client
* Conferences (with support for bookmarks)
* Address book integration
* Multiple accounts / unified inbox
* Very low impact on battery life


### XMPP Features

Conversations works with every XMPP server out there. However XMPP is an
extensible protocol. These extensions are standardized as well in so called
XEP's. Conversations supports a couple of these to make the overall user
experience better. There is a chance that your current XMPP server does not
support these extensions; therefore to get the most out of Conversations you
should consider either switching to an XMPP server that does or — even better —
run your own XMPP server for you and your friends. These XEP's are:

* [XEP-0065: SOCKS5 Bytestreams](http://xmpp.org/extensions/xep-0065.html) (or mod_proxy65). Will be used to transfer
  files if both parties are behind a firewall (NAT).
* [XEP-0163: Personal Eventing Protocol](http://xmpp.org/extensions/xep-0163.html) for avatars and OMEMO.
* [XEP-0191: Blocking command](http://xmpp.org/extensions/xep-0191.html) lets you blacklist spammers or block contacts
  without removing them from your roster.
* [XEP-0198: Stream Management](http://xmpp.org/extensions/xep-0198.html) allows XMPP to survive small network outages and
  changes of the underlying TCP connection.
* [XEP-0280: Message Carbons](http://xmpp.org/extensions/xep-0280.html) which automatically syncs the messages you send to
  your desktop client and thus allows you to switch seamlessly from your mobile
  client to your desktop client and back within one conversation.
* [XEP-0237: Roster Versioning](http://xmpp.org/extensions/xep-0237.html) mainly to save bandwidth on poor mobile connections
* [XEP-0313: Message Archive Management](http://xmpp.org/extensions/xep-0313.html) synchronize message history with the
  server. Catch up with messages that were sent while Conversations was
  offline.
* [XEP-0352: Client State Indication](http://xmpp.org/extensions/xep-0352.html) lets the server know whether or not
  Conversations is in the background. Allows the server to save bandwidth by
  withholding unimportant packages.
* [XEP-0363: HTTP File Upload](http://xmpp.org/extensions/xep-0363.html) allows you to share files in conferences
  and with offline contacts.

## FAQ

### General

#### How do I install Conversations?

Conversations is entirely open source and licensed under GPLv3. So if you are a
software developer you can check out the sources from GitHub and use Gradle to
build your apk file.

The more convenient way — which not only gives you automatic updates but also
supports the further development of Conversations — is to buy the App in the
Google [Play Store](https://play.google.com/store/apps/details?id=eu.siacs.conversations&referrer=utm_source%3Dgithub).

Buying the App from the Play Store will also give you access to our [beta test](#beta).

#### I don't have a Google Account but I would still like to make a donation

I’m listing several options to support me financially on [my website](https://gultsch.de/donate.html). Among other things [Liberapay](https://liberapay.com/iNPUTmice/donate), [GitHub Sponsors](https://github.com/sponsors/inputmice) and bank transfer.

#### How do I create an account?
XMPP, like email, is a federated protocol, which means that there is not one company you can create an *official XMPP account* with. Instead there are hundreds, or even thousands, of providers out there. One of those providers is our very own [conversations.im](https://account.conversations.im). If you don’t like to use *conversations.im* use a web search engine of your choice to find another provider. Or maybe your university has one. Or you can run your own. Or ask a friend to run one. Once you've found one, you can use Conversations to create an account. Just select *register new account* on server within the create account dialog.

##### Domain hosting
Using your own domain not only gives you a more recognizable Jabber ID, it also gives you the flexibility to migrate your account between different XMPP providers. This is a good compromise between the responsibilities of having to operate your own server and the downsides of being dependent on a single provider.

Learn more about [conversations.im Jabber/XMPP domain hosting](https://account.conversations.im/domain/). 

##### Running your own
If you already have a server somewhere and are willing and able to put the necessary work in you can run your own XMPP server.

As of 2019 we recommend you use [ejabberd](https://ejabberd.im). The default configuration file already enables everything you need to pass the [Conversations Compliance Suite](https://compliance.conversations.im). Make sure your Linux distribution ships a fairly recent version.

With a little bit of effort [Prosody](https://prosody.im) can be configured to support all necessary extensions as well. However you will have to rely on so called [Community Modules](https://modules.prosody.im/) of varying quality. Prosody can be interesting to people who like to modify their server and create / prototype own modules.

Performance wise - for small deployments - both ejabberd and Prosody should be fine. 

#### Where can I set up a custom hostname / port
Conversations will automatically look up the SRV records for your domain name
which can point to any hostname port combination. If your server doesn’t provide
those please contact your admin and have them read
[this](http://prosody.im/doc/dns#srv_records). If your server operator is unwilling
to fix this you can enable advanced server settings in the expert settings of
Conversations.

#### I get 'Incompatible Server'

As regular user you should be picking a different server. The server you selected
is probably insecure and/or very old.

If you are a server administrator you should make sure that your server provides
either STARTTLS or [XEP-0368: SRV records for XMPP over TLS](https://xmpp.org/extensions/xep-0368.html).

On rare occasions this error message might also be caused by a server not providing
a login (SASL) mechanism that Conversations is able to handle. Conversations supports
SCRAM-SHA1, PLAIN, EXTERNAL (client certs) and DIGEST-MD5.

#### I get 'Bind failure'. What does that mean?

Some Bind failures are transient and resolve themselves after a reconnect.

When trying to connect to OpenFire the bind failure can be a permanent problem when the domain part of the Jabber ID entered in Conversations doesn’t match the domain the OpenFire server feels responsible for. For example OpenFire is configured to use the domain `a.tld` but the Jabber ID entered is `user@b.tld` where `b.tld` also points to the same host. During bind OpenFire tries to reassign the Jabber to `user@a.tld`. Conversations doesn’t like that.
This can be fixed by creating a new account in Conversations that uses the Jabber ID `user@a.tld`. 

Note: This is kind of a weird quirk in OpenFire. Most other servers would just throw a 'Server not responsible for domain' error instead of attempting to reassign the Jabber ID.

Maybe you attempted to use the Jabber ID `test@b.tld` because `a.tld` doesn’t point to the correct host. In that case you might have to enable the extended connection settings in the expert settings of Conversations and set a host name.

#### I get 'Stream opening error'. What does that mean?

In most cases this error is caused by ejabberd advertising support for TLSv1.3 but not properly supporting it. This can happen if the OpenSSL version on the server already supports TLSv1.3 but the fast\_tls wrapper library used by ejabberd not (properly) support it. Upgrading fast\_tls and ejabberd or - theoretically - downgrading OpenSSL should fix the issue. A work around is to explicitly disable TLSv1.3 support in the ejabberd configuration. More information can be found on [this issue on the ejabberd issue tracker](https://github.com/processone/ejabberd/issues/2614).


#### I’m getting this annoying permanent notification
Starting with Conversations 2.3.6 Conversations releases distributed over the Google Play Store will display a permanent notification if you are running it on Android 8 and above. This is a rule that it is essentially enforced by the Google Play Store. (You won’t have the problem  of a *forced* foreground notification if you are getting your app from F-Droid.)

However you can disable the notification via settings of the operating system. (Not settings in Conversations.)

**The battery consumption and the entire behavior of Conversations will remain the same (as good or as bad as it was before). Why is Google doing this to you? We have no idea.**

##### Android &lt;= 7.1 or Conversations from F-Droid (all Android versions)
The foreground notification is still controlled over the expert settings within Conversations as it always has been. Whether or not you need to enable it depends on how aggressive the non-standard 'power saving' features are that your phone vendor has built into the operating system.

##### Android 8.x
Long press the permanent notification and disable that particular type of notification by moving the slider to the left. This will make the notification disappear but create another notification (this time created by the operating system itself.) that will complain about Conversations (and other apps) using battery. Starting with Android 8.1 you can disable that notification again with the same method described above.

##### Android 9.0+
Long press the permanent notification and press the info `(i)` button to get into the App info screen. In that screen touch the 'Notification' entry. In the next screen remove the checkbox for the 'Foreground service' entry. 

#### How do XEP-0357: Push Notifications work?
You need to be running the Play Store version of Conversations and your server needs to support push notifications.¹ Because *Google’s Firebase Cloud Messaging (FCM)* are tied with an API key to a specific app your server can not initiate the push message directly. Instead your server will send the push notification to the [Conversations App server](https://github.com/iNPUTmice/p2) (operated by us) which then acts as a proxy and initiates the push message for you. The push message sent from our App server through FCM doesn’t contain any personal information. It is just an empty message which will wake up your device and tell Conversations to reconnect to your server. The information sent from your server to our App server depends on the configuration of your server but can be limited to your account name. (In any case the Conversations App server won't redirect any information through FCM even if your server sends this information.)

In summary Google will never get hold of any personal information besides that *something* happened. (Which doesn’t even have to be a message but can be some automated event as well.) We - as the operator of the App server - will just get hold of your account name (without being able to tie this to your specific device).

If you don’t want this simply pick a server which does not offer Push Notifications or build Conversations yourself without support for push notifications. (This is available via a gradle build flavor.) Non-play store source of Conversations like the Amazon App store will also offer a version without push notifications. Conversations will just work as before and maintain its own TCP connection in the background.

You can find a detailed description of how your server, the app server and FCM are interacting with each other in the [README](https://github.com/iNPUTmice/p2/blob/master/README.md) of the Conversations App Server.

 ¹ If you use the Play Store version you do **not** need to run your own app server. Your server only needs to support the server side of [XEP-0357: Push Notifications](http://xmpp.org/extensions/xep-0357.html) and [XEP-0198: Stream Management](https://xmpp.org/extensions/xep-0198.html). The prosody server modules are called *mod_cloud_notify* and *mod_smacks*. The ejabberd server modules are called *mod_push* and *mod_stream_mgmt*.


#### But why do I need a permanent notification if I use Google Push?
FCM (Google Push) allows an app to wake up from *Doze* which is (as the name suggests) a hibernation feature of the Android operating system that cuts the network connection and also reduces the number of times the app is allowed to wake up (to ping the server for example). The app can ask to be excluded from doze. Non push variants of the app (from F-Droid or if the server doesn’t support it) will do this on first start up. So if you get exemption from *Doze*, or if you get regular push events sent to you, Doze should not pose a threat to Conversatons working properly. But even with *Doze* the app is still open in the background (kept in memory); it is just limited in the actions it can do. Conversations needs to stay in memory to hold certain session state (online status of contacts, join status of group chats, …). However with Android 8 Google changed all of this again and now an App that wants to stay in memory needs to have a foreground service which is visible to the user via the annoying notification. But why does Conversations need to hold that state? XMPP is a statefull protocol that has a lot of per-session information; packets need to be counted, presence information needs to be held, some features like Message Carbons get activated once per session, MAM catch-up happens once, service discovery happens only once; the list goes on. When Conversations was created in early 2014 none of this was a problem because apps were just allowed to stay in memory. Basically every XMPP client out there holds that information in memory because it would be a lot more complicated trying to persist it to disk. An entire rewrite of Conversations in the year 2019 would attempt to do that and would probably succeed however it would require exactly that; a complete rewrite which is not feasible right now. That’s by the way also the reason why it is difficult to write an XMPP client on iOS. Or more broadly put this is also the reason why other protocols are designed as or migrated to stateless protocols (often based on HTTP); take for example the migration of IMAP to [JMAP](https://jmap.io/).

#### Conversations doesn’t work for me. Where can I get help?

You can join our conference room on [`conversations@conference.siacs.eu`](https://conversations.im/j/conversations@conference.siacs.eu).
A lot of people in there are able to answer basic questions about the usage of
Conversations or can provide you with tips on running your own XMPP server. If
you found a bug or your app crashes please read the Developer / Report Bugs
section of this document.

#### I need professional support with Conversations or setting up my server

I'm available for hire. Contact information can be found on [my website](https://gultsch.de).

#### How does the address book integration work?

The address book integration was designed to protect your privacy. Conversations
neither uploads contacts from your address book to your server nor fills your
address book with unnecessary contacts from your online roster. If you manually
add a Jabber ID to your phones address book Conversations will use the name and
the profile picture of this contact. To make the process of adding Jabber IDs to
your address book easier you can click on the profile picture in the contact
details within Conversations. This will start an "add to address book" intent
with the JID as the payload. This doesn't require Conversations to have write
permissions on your address book but also doesn't require you to copy/paste a
JID from one app to another.

#### I get 'delivery failed' on my messages

If you get delivery failed on images it's probably because the recipient lost
network connectivity during reception. In that case you can try it again at a
later time.

For text messages the answer to your question is a little bit more complex.
When you see 'delivery failed' on text messages, it is always something that is
being reported by the server. The most common reason for this is that the
recipient failed to resume a connection. When a client loses connectivity for a
short time the client usually has a five minute window to pick up that
connection again. When the client fails to do so because the network
connectivity is out for longer than that all messages sent to that client will
be returned to the sender resulting in a delivery failed.

Instead of returning a message to the sender both ejabberd and prosody have the
ability to store messages in offline storage when the disconnecting client is
the only client. In prosody this is available via an extra module called
```mod_smacks_offline```. In ejabberd this is available via some configuration
settings.

Other less common reasons are that the message you sent didn't meet some
criteria enforced by the server (too large, too many). Another reason could be
that the recipient is offline and the server doesn't provide offline storage.

Usually you are able to distinguish between these two groups in the fact that
the first one happens always after some time and the second one happens almost
instantly.

#### Where can I see the status of my contacts? How can I set a status or priority?

Statuses are a horrible metric. Setting them manually to a proper value rarely
works because users are either lazy or just forget about them. Setting them
automatically does not provide quality results either. Keyboard or mouse
activity as indicator for example fails when the user is just looking at
something (reading an article, watching a movie). Furthermore automatic setting
of status always implies an impact on your privacy (are you sure you want
everybody in your contact list to know that you have been using your computer at
4am‽).

In the past status has been used to judge the likelihood of whether or not your
messages are being read. This is no longer necessary. With Chat Markers
(XEP-0333, supported by Conversations since 0.4) we have the ability to **know**
whether or not your messages are being read.  Similar things can be said for
priorities. In the past priorities have been used (by servers, not by clients!)
to route your messages to one specific client. With carbon messages (XEP-0280,
supported by Conversations since 0.1) this is no longer necessary. Using
priorities to route OTR messages isn't practical either because they are not
changeable on the fly. Metrics like last active client (the client which sent
the last message) are much better.

Unfortunately these modern replacements for legacy XMPP features are not widely
adopted. However Conversations should be an instant messenger for the future and
instead of making Conversations compatible with the past we should work on
implementing new, improved technologies and getting them into other XMPP clients
as well.

Making these status and priority optional isn't a solution either because
Conversations is trying to get rid of old behaviours and set an example for
other clients.

#### Translations
Translations are managed on [Transifex](https://www.transifex.com/projects/p/conversations/).
If you want to become a translator  Please register on transifex, apply to join
the translation team and then step by our group chat on
[conversations@conference.siacs.eu](https://conversations.im/j/conversations@conference.siacs.eu)
and introduce yourself to `iNPUTmice` so he can approve your join request.

#### How do I backup / move Conversations to a new device?
On the one hand Conversations supports Message Archive Management to keep a server side history of your messages so when migrating to a new device that device can display your entire history. However that does not work if you enable OMEMO due to its forward secrecy. (Read [The State of Mobile XMPP in 2016](https://gultsch.de/xmpp_2016.html) especially the section on encryption.)

As of version 2.4.0 an integrated Backup & Restore function will help with this, go to Settings and you’ll find a setting called Create backup. A notification will pop-up during the creation process that will announce you when it's ready. After the files, one for each account, are created, you can move the **Conversations** folder *(if you want your old media files too)* or only the **Conversations/Backup** folder *(for OMEMO keys and history only)* to your new device (or to a storage place) where a freshly installed Conversations can restore each account. Don't forget to enable the accounts after a successfull restore.

This backup method will include your OMEMO keys. Due to forward secrecy you will not be able to recover messages sent and received between creating the backup and restoring it. If you have a server side archive (MAM) those messages will be retrieved but displayed as *unable to decrypt*. For technical reasons you might also lose the first message you either sent or receive after the restore; for each conversation you have. This message will then also show up as *unable to decrypt*, but this will automatically recover itself as long as both participants are on Conversations 2.3.11+. Note that this doesn’t happen if you just transfer to a new phone and no messages have been exchanged between backup and restore.

In the vast, vast majority of cases you won’t have to manually delete OMEMO keys or do anything like that. Conversations only introduced the official backup feature in 2.4.0 after making sure the *OMEMO self healing* mechanism introduced in 2.3.11 works fine.

**WARNING**: Be sure to know your accounts passwords or find ways to reset them **before** doing the backup as the files are encrypted using those passwords and the Restore process will ask for them.  
**WARNING**: Do not use the restore backup feature in an attempt to clone (run simultaneously) an installation. Restoring a backup is only meant for migrations or in case you’ve lost the original device.

#### Conversations is missing a certain feature

I'm open for new feature suggestions. You can use the [issue tracker][issues] on
GitHub.  Please take some time to browse through the issues to see if someone
else already suggested it. Be assured that I read each and every ticket. If I
like it I will leave it open until it's implemented. If I don't like it I will
close it (usually with a short comment). If I don't comment on an feature
request that's probably a good sign because this means I agree with you.
Commenting with +1 on either open or closed issues won't change my mind, nor
will it accelerate the development.

#### You closed my feature request but I want it really really badly

Just write it yourself and send me a pull request. If I like it I will happily
merge it if I don't at least you and like minded people get to enjoy it.

#### I need a feature and I need it now!

I am available for hire. Find contact information on [my website](https://gultsch.de).

### Security

#### Why are there two end-to-end encryption methods and which one should I choose?

* OMEMO works even when a contact is offline, and works with multiple devices. It also allows asynchronous file-transfer when the server has [HTTP File Upload](http://xmpp.org/extensions/xep-0363.html). However, OMEMO not widely support and is currently implemented only [by a handful of clients](https://omemo.top).
* OpenPGP (XEP-0027) is a very old encryption method that has some advantages over OMEMO but should only be used by people who know what they are doing.

#### How do I use OpenPGP

Before you continue reading you should note that the OpenPGP support in
Conversations is experimental. This is not because it will make the app unstable
but because the fundamental concepts of PGP aren't ready for widespread use.
The way PGP works is that you trust Key IDs instead of JID's or email addresses.
So in theory your contact list should consist of Public-Key-IDs instead of
JID's. But of course no email or XMPP client out there implements these
concepts. Plus PGP in the context of instant messaging has a couple of
downsides: It is vulnerable to replay attacks and it is rather verbose.

To use OpenPGP you have to install the open source app
[OpenKeychain](http://www.openkeychain.org) and then long press on the account in
manage accounts and choose renew PGP announcement from the contextual menu.

#### OMEMO is grayed out. What do I do?
OMEMO is only available in 1:1 chats and private (members-only, non-anonymous) group chats. Encrypting public group chats makes little to no sense since anyone (including a hypothetical attacker) can join and a user couldn’t possibily verify all participants anyway. Furthermore for a lot of public group chat it is desirable to give new comers access to the full history.

#### OMEMO doesn’t work. I get a 'Something went wrong' message in the 'Trust OMEMO Fingerprints' screen.
OMEMO has two requirements: Your server and the server of your contact need to support PEP. Both of you can verify that individually by opening your account details and selecting ```Server info``` from the menu. The appearing table should list PEP as available. The second requirement is that the initial sender needs to have access to the published key material. This can either be achieved by having mutual presence subscription (you can verify that by opening the contact details and see if both check boxes *Send presence updates* and *Receive presence updates* are checked) or by using a server that makes the public key material accessible to anyone. In the [Compliance Tester](https://compliance.conversations.im) this is indicated by the 'OMEMO' feature. Since it is very common that the first messages are exchanged *before* adding each other to the contact list it is desirable to use servers that have 'OMEMO support'.

#### How does the encryption for group chats work?

##### OMEMO

OMEMO encryption works only in private (members only) conferences that are non-anonymous. Non-anonymous (being able to discover the real JID of other participants) is a technical requirement to discover the key material. Members only is a sort of arbitrary requirement imposed by Conversations. (see 'OMEMO is grayed out')

The server of all participants need to pass the OMEMO [Compliance Test](https://conversations.im/compliance/).
In other words they either need to run ejabberd 18.01+ or Prosody 0.11+.

(Alternatively it would also work if all participants had each other in their contact list; But that rarely is the case in larger group chats.)

The owner of a conference can make a public conference private by going into the conference
details and hit the settings button (the one with the gears) and select both *private* and
*members only*.

##### OpenPGP

Every participant has to announce their OpenPGP key (see answer above).
If you would like to send encrypted messages to a conference you have to make
sure that you have every participant's public key in your OpenKeychain.
Right now there is no check in Conversations to ensure that.
You have to take care of that yourself. Go to the conference details and
touch every key id (The hexadecimal number below a contact). This will send you
to OpenKeychain which will assist you on adding the key.  This works best in
very small conferences with contacts you are already using OpenPGP with. This
feature is regarded experimental. Conversations is the only client that uses
XEP-0027 with conferences. (The XEP neither specifically allows nor disallows
this.)

#### What is Blind Trust Before Verification / why are messages marked with a red lock?

Read more about the concept on https://gultsch.de/trust.html

#### What happened to OTR support?
OTR was removed because it was highly unreliable. It didn’t work with multiple devices and was never really specified to work with XMPP. The codebase was a mess (There was an HTML parser in there for crying out loud to deal with the garbage some OTR clients would send.) Verification was implemented in a non-blocking way. It would tell you if the current session was using an unknown fingerprint but it didn’t actively stopped you from sending messages until you have confirmed the new fingerprint. (Like Conversations would do now with BTBV after verification or when BTBV is turned off.) Considering the previous points there was little to no desire from my point to fix this potential security issue or clean up the code base. Another reason for the removal was that people would use it *accidentally* even to communicate between two Conversations clients because they read somewhere that OTR is good.

### What clients do I use on other platforms
There are XMPP Clients available for all major platforms.
#### Windows / Linux
For your desktop computer we recommend that you use [Gajim](https://gajim.org). You need to install the plugins `OMEMO`, `HTTP Upload` and `URL image preview` to get the best compatibility with Conversations. Plugins can be installed from within the app.
#### iOS
Unfortunately we don‘t have a recommendation for iPhones right now. There are three clients available [Siskin](https://siskin.im/), [ChatSecure](https://chatsecure.org/) and [Monal](https://monal.im/). Each with their own pros and cons.


### Development

<a name="beta"></a>
#### Beta testing
If you bought the App on [Google Play](https://play.google.com/store/apps/details?id=eu.siacs.conversations)
you can get access to the the latest beta version by signing up using [this link](https://play.google.com/apps/testing/eu.siacs.conversations).

#### How do I build Conversations

##### Compiling WebRTC.

WebRTC is a standard for Internet audio and video communication. libwebrtc, also used in the Google Chrome web browser, implementing the WebRTC standard.

**Note:** Starting with version 2.8.0 you will need to compile libwebrtc from source because there are no fresh binary releases available to download.

[Instructions](https://webrtc.github.io/webrtc-org/native-code/android/) can be found on the WebRTC website, however, there build method used by Conversations developers is slightly different.

```
mkdir -p ~/Prerequisites-for-Conversations
cd ~/Prerequisites-for-Conversations
git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git
export PATH=~/Prerequisites-for-Conversations/depot_tools:$PATH
mkdir webrtc
cd webrtc
fetch --nohooks webrtc_android
# ...wait for 20Gb of stuff...
gclient sync
# ...wait for more 5Gb of stuff...
cd src
unset _JAVA_OPTS
./tools_webrtc/android/build_aar.py
```

It will take some time and build webrtc for all popular Android architectures.
The result will be the file `./libwebrtc.aar`


##### Building Conversations itself

Place the resulting libwebrtc.aar in the `libs/` directory. The PlayStore release currently
uses the stable M90 release and renamed the file name to `libwebrtc-m90.aar` put potentially you can
reference any file name by modifying `build.gradle`. Search for `libwebrtc-m90.aar`, and replace it with `libwebrtc.aar`.


Make sure to have ANDROID_HOME point to your Android SDK. Use the Android SDK Manager to install missing dependencies.

Alternatively (and to avoid thinking about environment variables), create a file called local.properties, in the root of the Conversations build tree,
with the following contents:

```
## This file must *NOT* be checked into Version Control Systems,
# as it contains information specific to your local configuration.
#
# Location of the SDK. This is only used by Gradle.
# For customization when using a Version Control System, please read the
# header note.
#Wed May 20 16:21:35 CST 2020
ndk.dir=Path-To-Ndk
sdk.dir=Path-To-Sdk
```

Then issue the following commands in order to build the apk.

    git clone https://github.com/inputmice/Conversations.git
    cd Conversations
    ./gradlew assembleConversationsFreeSystemDebug

There are two build flavors available. *free* and *playstore*. Unless you know what you are doing you only need *free*.

You will find the apks in the `./build/outputs/apk/conversationsFreeSystem/debug/` directory.

Be careful, the resulting apks will not install unless you delete your existing Conversations installation (which will delete all the messages from your phone, and if you have used OMEMO, you will not be able to restore them from the server).
Do it at your own risk.

You, though, can make your own build a "test build", that can be installed alongside the normal (F-Droid or Google Play) Conversations:

In the file `build.gradle`, find the line `applicationId "eu.siacs.conversations"` , and replace it with `applicationId "my.conversations.fork"`, also below replace "Conversations" appName with "MyCFork".
Then the resulting APK can be installed ALONGSIDE normal Conversations. And have a different name so it's not confusing

WARNING: DO NOT REPLACE ANYTHING ELSE ANYWHERE ELSE, DO NOT REPLACE THIS PROJECT WIDE. JUST 2 strings in THAT specific file!

[![Build Status](https://travis-ci.org/inputmice/Conversations.svg?branch=development)](https://travis-ci.org/inputmice/Conversations)

#### How do I debug Conversations

If something goes wrong Conversations usually exposes very little information in
the UI (other than the fact that something didn't work). However with adb
(android debug bridge) you can squeeze some more information out of Conversations.
These information are especially useful if you are experiencing trouble with
your connection or with file transfer.

To use adb you have to connect your mobile phone to your computer with an USB cable
and install `adb`. Most Linux systems have prebuilt packages for that tool. On
Debian/Ubuntu for example it is called `android-tools-adb`.

Furthermore you might have to enable 'USB debugging' in the Developer options of your
phone. After that you can just execute the following on your computer:

    adb -d logcat -v time -s conversations

If need be there are also some Apps on the PlayStore that can be used to show the logcat
directly on your rooted phone. (Search for logcat). However in regards to further processing
(for example to create an issue here on Github) it is more convenient to just use your PC.

#### I found a bug

Please report it to our [issue tracker][issues]. If your app crashes please
provide a stack trace. If you are experiencing misbehavior please provide
detailed steps to reproduce. Always mention whether you are running the latest
Play Store version or the current HEAD. If you are having problems connecting to
your XMPP server your file transfer doesn’t work as expected please always
include a logcat debug output with your issue (see above).

[issues]: https://github.com/inputmice/Conversations/issues
