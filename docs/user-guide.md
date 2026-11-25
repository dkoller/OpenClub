# OpenClub user guide

For the people who use a club's OpenClub: administrators, team managers, members and guardians. Button and field names
are the English ones; switch the language (German, English, French, Italian) with the language box (*EN*, *DE*, *FR*,
*IT*) at the top of the page.

> **Fictional data only.** OpenClub is in a synthetic-data test phase. Do not enter real people's names, birth dates,
> addresses or phone numbers until the club's privacy approval is complete (see the [README](../README.md)).

## Finding your way

The app shows one page at a time. At the top are the club's name (back to *Start*), the language box and *Sign out*.
The main menu sits at the bottom of a phone screen and on the left of a computer screen:

| Page | What it is for |
| --- | --- |
| *Start* | *Things for you to do*: every event that still needs your answer, or your child's, with *Yes*, *No* and *Maybe* right there, and a free seat to book for an event you said *Yes* to; then what else is on in the next 7 days, and the announcements |
| *Schedule* | The next eight weeks by day. With children, choose *Everyone*, *Me* or one child. Each event opens its own page with the answers, helper shifts and rides |
| *Family* | A short list that opens each part on its own page (*← Family* leads back): *My profile*, *My children* (only for guardians, with their first names), *Absences*, *Fees* (with how many are open), *Calendar subscription* and *Email notifications* |
| *Team* | Only for coaches and team managers (`VIEW_TEAMS` or `MANAGE_TEAMS`): *Next events* with who has not answered, *Helpers missing* (for managers), then *Teams*, one row per team. A team opens its own page (*← All teams* leads back) with *Schedule*, *Members* and *Statistics*, each on its own page, and *Manage team* |
| *Administration* | Only with an administration permission: *Waiting for you* (open join requests, helper shifts that still need people, unpaid fees), *Members*, *Join requests*, *Fees*, *Permissions* and *Settings*, each shown only to people who may use it |

**On the home screen:** in the phone's browser choose *Add to Home Screen* (Safari: share button; Chrome: menu). The
club page then opens like an app, full screen with its own icon. It always loads fresh from the club's server, so
it needs a connection.

Every page has its own address, so the browser's back button and bookmarks work (for example `#/schedule`, or
`#/team/<team>/schedule` for one team's schedule). On a
phone, wide lists such as fees or the import preview show one card per row instead of a table.

What you see depends on your permissions: menu entries and panels you cannot use are not shown at all. Every action is checked by the
server. Every change to member data and every read of someone's private details is also recorded in an audit trail
that holds identifiers only (team schedules hold no personal data and are not recorded).

## Signing in

Choose *Sign in*. You are sent to the club's sign-in service (Keycloak or Rauthy), then back to the club page.

- You are a member: *Start* opens with what needs your answer.
- *This account is not yet a club member.* Your account works, but no member record is linked to it. Ask the club for
  an invitation, or paste one you received into *Accept an invitation*.

*Sign out* ends your session on this device.

## For administrators

Administrators are members who hold permissions. Permissions are separate from the *Administrator* role: the role
alone allows nothing, and each panel needs its permission (see *Permissions* below).

All of this is under *Administration*. It opens on *Waiting for you*, which lists what needs a decision (open join
requests, helper shifts of the next 14 days that still need people, fee periods with unpaid fees) with a button
straight to the right section.

### Members (*Administration* → *Members*, panel *Manage members*, permission `EDIT_MEMBERS`)

*Members* shows the member list and form. The other member tasks each have their own page, linked in the row of
buttons above the list (*More tasks*) and shown only to people who may use them: *Invitations and accounts*,
*Guardians*, *Club roles* (with `MANAGE_PERMISSIONS`), *Excel import and export*, *Archive* and *Public member list*.
Each page has *← Members* to go back.

- **Find:** type into *Search names* and choose *Search*.
- **Add:** fill *First name*, *Last name*, *Date of birth* and, for adults, *Email* and *Phone*, then *Add member*.
  Tick *Player* for players; if the club requires it, a *Gender (free text)* is then needed. The server refuses
  incomplete records with *Please check the fields…*.
- **Details:** *Details* shows the member's stored fields, whether a sign-in is linked and whether an invitation is
  open. **Export all data (JSON)** downloads everything stored about the member, for a person's request to see or take
  their data. The file contains personal data: give it only to the verified person, then delete it. Every export is
  recorded.
- **Edit:** *Edit* opens a form with all current values; change what you need and *Save*. Values you did not change
  stay as they are. If someone else changed the member while your form was open (for example a guardian updating a
  phone number), nothing is saved and you are asked to edit again, so their change is not overwritten. Untick *Show on
  public member list* when a member (or a child's guardian) objects to being on the public list.
  *J+S person number* holds the member's number from the national sport database (digits only); coaches need it
  for the J+S attendance file and never see it themselves. The member sees it under *My profile*.
- **Archive:** *Archive*, then *Confirm archive*. Archived members disappear from lists and lose their permissions,
  team places and guardianships. Only members with the *Member* role and no linked sign-in can be archived: unlink
  their sign-in first (see *Invitations*). Right after archiving, *Undo archive* brings them back.
- **Archived members:** the *Archive* page lists everyone archived earlier with the date. *Reactivate* makes them active
  again, **without** their former permissions, teams or sign-in. If the record no longer meets the club's rules (for
  example an adult without a phone number), reactivation is refused.
- Deleting a member for good is done by the server operator, not in the app.
- **Excel:** on the *Excel import and export* page, *Download member list (CSV for Excel)* saves all active members as a file
  Excel opens directly (phone numbers start with an apostrophe so Excel does not treat them as formulas). To import,
  save your sheet in Excel with *Save As* → *CSV UTF-8*; it needs the columns First name, Last name and Date of birth
  (31.12.2010), and may have Email, Phone, Gender and Player (yes/no), with headings in any of the four languages.
  *Check file* shows every row: *will be imported*, *already exists* (same name and birth date; left out) or *has
  problems* with the reason (left out). Nothing is created until you choose *Import N members*. Existing members are
  never changed. Every export and import is recorded.

### Teams (*Team* page, section *Teams*, then each team's own page)

- *Team name* and *Add team* under *Teams* create a team (only shown to people who may; after a refused attempt the
  form disappears). Each team is one row with its number of members; tap it to open the team's page.
- On the team's page, *Manage team* has *Rename* and *Delete team* (type its name to confirm). Deleting a team also
  deletes its schedule; you are taken back to the list. Both disappear if the server refuses them.
- *Schedule* on the team's page shows and plans its trainings and events (see *For team managers*).
- *Members* on the team's page lists the team. Find a member and *Assign*; *Remove* takes them out of the team.
- *Contact details* shows a team member's email and phone (see *For team managers*).

### Permissions (*Administration* → *Permissions*, permission `MANAGE_PERMISSIONS`)

Find a member, choose the *Scope* (*Club*, or one *Team*) and the *Capability*, then *Grant*. *Current grants* lists
what they hold; *Revoke* removes one. A change applies to the member's next action.

| Capability | Allows |
| --- | --- |
| `VIEW_MEMBER_DIRECTORY` | Searching members by name |
| `EDIT_MEMBERS` | Adding, editing, archiving, reactivating and exporting members; invitations; guardians |
| `VIEW_TEAMS` | Seeing teams, their members and their schedules (club-wide or one team) |
| `MANAGE_TEAMS` | Managing teams, assignments and schedules, and reading the contacts of active team members (club-wide or one team) |
| `MANAGE_PERMISSIONS` | Granting and revoking permissions; with the *Administrator* role also unlinking sign-ins |
| `MANAGE_FEES` | Managing membership fees (club-wide only) |
| `POST_ANNOUNCEMENTS` | Posting announcements to the whole club (club-wide only); team announcements need `MANAGE_TEAMS` for the team |

*Club roles* (panel *Manage club roles*) switches a member between *Member* and *Administrator*. The last administrator cannot be demoted.

### Invitations (*Administration* → *Members* → *Invitations and accounts*)

1. Find the member and choose *Create invitation*.
2. Copy the *Invitation link (shown only once)* and send it to the member through a channel you trust. It is valid for
   seven days, works once and only together with the member's own sign-in.
3. *Revoke invitation* cancels an open link. *Unlink account* disconnects a member's sign-in and ends their session at
   once; it needs `MANAGE_PERMISSIONS` **and** the *Administrator* role.

Minors and archived members cannot be invited, and an administrator's sign-in cannot be unlinked here.

### Guardians (*Administration* → *Members* → *Guardians*)

1. *Find a minor*, then *Search*; the minor's current guardians are listed.
2. *Find a guardian* (an adult member who already signs in), select them and choose *Add as guardian*. By doing so you
   confirm that you checked the relationship outside OpenClub; the app cannot check it.
3. *Remove* ends a guardianship. A minor has at most two guardians. Guardianships end on their own when the child
   reaches the club's adult age, or when either person is archived or the guardian's sign-in is unlinked.

Being a guardian gives no other permission in the club.

### Membership fees (*Administration* → *Fees*, permission `MANAGE_FEES`)

Tracking only: OpenClub records who paid, it does not collect money.

- **New period:** *Name* (for example "Season 2026/27"), *Due date* and *Amount (CHF)*, then *Create period*. Every
  active member gets a fee at that amount. The list shows each period with how many paid and the amounts.
- **Open** a period to see the fees; *Show* filters *All*, *Open*, *Paid* or *Excluded*. Per member: *Mark paid*
  (today) or *Mark unpaid*, *Change amount* (for example a reduced fee), *Exclude* (for example an honorary member),
  and for excluded members *Include*. Archived members keep their fee and can still be marked paid.
- *Delete period* removes the period with all its fees; type its name to confirm.
- Every change is recorded in the audit trail. Fees are part of a member's data export.
- **Payment details for QR bills** (below the periods): enter the club's *IBAN or QR-IBAN* (Switzerland or
  Liechtenstein), the *Account holder*, optionally *Street* and *Building number*, *Postal code*, *Town* and *Country*,
  then *Save payment details*. From then on every member (and guardian) can download a Swiss QR bill for each open fee.
  With a QR-IBAN the bill carries a 27-digit QR reference, otherwise a creditor reference starting with *RF*. The
  *Reference* column of an opened period shows each member's reference, so you can match a payment in the bank
  statement; then *Mark paid* by hand. A member excluded and included again gets a new reference.

### Fields and halls (*Administration* → *Fields and halls*, permission `MANAGE_VENUES`)

Add the club's fields and halls by name (*Add*), *Rename* or *Delete* them. Deleting one keeps its events, without the
place. The first administrator holds `MANAGE_VENUES`; give it to others under *Permissions*.

### Club settings (*Administration* → *Settings*, permission `MANAGE_PERMISSIONS`)

- *Edit club name*.
- *Adult age*: from which age members count as adults (13 to 25, default 18). Adults need email and phone, so raising
  the number is refused while members who would become adults lack them.
- *Player gender rule*: *Require gender for players*. Turning it on is refused while players without a gender exist.

## For team managers

**Next events.** The *Team* page starts with the next 14 days of your teams' events (at most eight teams are
included), each with its totals and how many have not answered yet (*11 yes · 2 no · 3 open*). Open one to see *Who is
coming?*: the totals side by side, then the names under *No answer yet*, *No*, *Maybe* and *Yes* (adults by full name,
minors by first name and initial). Absences count as *No*; no reason is ever shown. With `MANAGE_TEAMS`, *Remind all 3
without an answer* emails them (parents for their children) if the club sends email, at most once every 12 hours; an
automatic reminder also goes out two days before every event. The same page lists the event's helper shifts and rides.

**Schedule.** On the *Team* page, open a team and choose *Schedule* to see its next eight weeks, one row per event
with its date, time, place and totals. A row opens the event's coach page (the same as from *Next events*): who is
coming, helper shifts and rides. With `MANAGE_TEAMS` for the
team you add events under *Add event* at the bottom: *Kind* (Training, Match or Event), *Title*, *Date*, *Start*, *End*, optionally *Place* and
*Notes*, then *Add event*. Fill *Repeat weekly until* to create the same event every week up to that date (at most
52). *Edit* changes one date, *Cancel event* marks it cancelled (members still see it, marked *Cancelled*;
*Reinstate* undoes this) and *Delete* followed by *Confirm delete* removes it. With `VIEW_TEAMS` you see the
schedule without these buttons. Each event shows the totals (*Answers: 5 Yes, 1 No, 2 Maybe*); its page lists every
team member's answer by name (minors by first name and initial), the ones without an answer first. Times are entered
and shown in your device's time zone; weekly repeats keep the same local time across the change to and from summer
time.

**Attendance statistics.** With `VIEW_TEAMS` or `MANAGE_TEAMS` for a team, *Statistics* on the team's page shows, for the last 12 months (change *From* and *To*, at most a year, then *Show*), how many events took place and
how each member answered them: *Yes*, *No*, *Maybe* and *No answer*, with totals. Cancelled events do not count.
Answers are deleted 12 months after an event, and members who joined later show earlier events as *No answer*.

**Helpers missing.** With `MANAGE_TEAMS`, the *Team* page starts with the helper shifts of your teams in the next 14
days that still have free places (*Bar: 2 of 4 places free*), with the event, team and time. Only the numbers are
shown, not who signed up. A club-wide manager also sees them on *Waiting for you*. Seven days before such a shift,
the team and the parents of its children are also asked by email (*Helpers wanted*, if the club sends email).

**Volunteer shifts.** An event's page (from *Next events* or from the team's *Schedule*) lists its helper jobs (for example *Bar*, 14:00–16:00,
*2 of 3 filled*) with the names of who signed up. With `MANAGE_TEAMS` for the team you add a shift: *Task*, *Date*,
*Start*, *End* (prefilled with the event's times, at most 24 hours and within a day of the event) and *People needed*,
then *Add shift*. *Edit* changes a shift (you cannot lower the places below the people already signed up), *Delete*
followed by the confirmation removes it with its sign-ups.

**Message to the team.** On an event's page, managers open *Message to the team*, choose *Everyone in the team*,
*Everyone who said yes*, *Everyone without an answer* or (for a match) *The squad*, write a *Subject* and the
*Message*, and *Send*. The server emails them (parents for their children) with your name; nobody sees any address.
At most 10 messages per team and day.

**Fields and halls.** If the club keeps a list of fields and halls, the event form has *Field or hall*. A slot that is
already booked is refused with the other event's team, title and time, so you can pick another time or place. *Change
several events* can also move events to *Another field or hall*. Everyone can see when each one is booked under
*Schedule* → *When fields and halls are booked*.

**Change several events.** At the bottom of a team's *Schedule*, managers open *Change several events*: choose *From*,
*To*, which kinds (*Trainings*, *Matches*, *Other events*) and what to do (*Cancel*, *Reinstate* or *New place*), then
*Check*. It says how many events are affected; *Change 6 events* applies it. Only events that have not started are
changed, and everyone affected gets a single email listing them.

**Squad (Aufgebot).** For a match, an event's page shows *Squad* to the team's managers until the match starts: tick
who is called up (*Select everyone who said yes* is a shortcut), optionally set *Meet at* and *Meeting point*, then
*Save squad*. Everyone called for the first time gets an email (parents for their children); removing someone sends
nothing. Only you see the list: each player and parent sees only *Called up* (with the meeting time and place) or
*Not called up* for themselves or their child, on the event page and as a tag on *Schedule*.

**Who came (J+S).** With `MANAGE_TEAMS`, an event's page gets *Who came?* once the event has started: tick the
leaders and participants who were there (*Tick everyone who said yes* is a shortcut) and press *Save attendance*.
You can correct it later. On the team's *Statistics* page, *J+S attendance* checks a date range (*Check*): how many
rows the file has, who has no J+S person number yet (they are left out; an administrator adds the number under
*Members* → *Edit*) and which trainings have no place. *Download file* gives the CSV for the import of the attendance
record (*Anwesenheitskontrolle*) in the NDS. Trainings become *Training* with time, duration and place, matches
*Wettkampf*; other events are left out. Every download is recorded.

With `MANAGE_TEAMS` for a team you manage its assignments on its *Members* page and can open *Contact details* of its **active** members
(name, email, phone, player). Members of other teams, archived members and everyone's birth date stay hidden. Each
contact read is recorded.

## Announcements

*Announcements* on *Start* lists what is currently posted for you: club-wide news, news for your teams and, for
guardians, your children's teams, newest first, until each one's show-until date. With `POST_ANNOUNCEMENTS` (whole
club) or `MANAGE_TEAMS` for a team, choose *For*, write a *Title* and *Text* (plain text, line breaks are kept),
optionally *Show until* (default 60 days, at most a year), and *Post*. *Edit* and *Delete* (then *Confirm delete*)
appear on the ones you may change. Do not put private details such as health or addresses into announcements.

## For members

Once your invitation is accepted you are signed in as a club member. Without further permissions you see the club and
its public member list (adults by full name, minors by first name and surname initial, nothing private).

**Start** shows *Things for you to do*: each event that still needs an answer from you or your child, soonest first,
with *Yes*, *No* and *Maybe* in the card. One tap answers; the card then leaves the list. Below it, *Also in the next 7
days* lists what is already answered, and the announcements follow.

**Schedule** lists the next eight weeks of trainings, matches and events of your teams, and of your children's
teams if you are a guardian, grouped by day. With children, the buttons at the top show *Everyone*, *Me* or one child.
Events still waiting for an answer are marked *Answer missing*. Answer *Yes*, *No* or *Maybe* until the event starts;
choosing your answer again clears it. You see the totals, but never other members' answers. A match where you or
your child are called up shows *Called up*; its page has the meeting time and place.

**One event.** Tap an event (or *Rides, helpers and details*) to open its page: when and where, notes, the answers for
you and your children, the team's totals, helper shifts and rides. *Sign up* puts you (or your child) on a
shift while places are free, *Withdraw* takes you off, both until the shift starts. Your team sees your name on the
shift so everyone can coordinate.

**Email notifications** (*Family* → *Email notifications*): if your club sends email, you get one when an event of your team (or your
child's team) is changed or cancelled, when new events are added, when an announcement is posted for you, and a
reminder the day before a volunteer shift you signed up for. *Helpers wanted*: seven days before a helper shift of
your team (or your child's) that still has free places, you get one email per event listing those shifts with the
free places and a link; not if you (or your child) already help at that event. *Answer missing*: two days before
an event that still lacks your answer (or your child's), and when the coach sends a reminder. *Called up for a match*: when you
or your child are added to a match squad. *Messages from the coach*: messages a coach sends to your team. Untick what you do not want and choose the language of
the emails; changes save at once. You need a sign-in and an email address to receive them.

**Membership applications** (*Administration* → *Join requests*, with `EDIT_MEMBERS`): tick *Public application form is on* to show *Join the club* on
the signed-out start page, where adults apply for themselves and parents for a child. Open applications are listed
with contact details; *Accept* creates the member (a child without contacts: link the parent as guardian afterwards)
and puts them in the wished team; *Decline* and the confirmation delete the application. Untick the switch to stop
new applications. Unhandled applications are deleted after 60 days.

**Carpooling.** If you (or your child) said *Yes* to an event in the next 7 days and have no seat yet, *Start* shows
a ride there that still has a free seat (*Ride for Lea*), with *Book seat* and *All rides*. An event's page also lists
the rides offered to it: driver, direction, departure,
meeting point and seats. If you are in the team (or your child is), *Offer a ride* with direction, seats, meeting
point, date and time; change it later with *Save changes* or *Withdraw ride*. *Book seat* reserves a seat for you or
your child until departure. Once booked, you see the driver's phone number and the driver sees yours; nobody else
does.

**Absences** (*Family* → *Absences*): enter the days you (or your child) are away, *From* and optionally *To*, then
*Add absence*. Every event on those days that you have not answered counts as *No*; answering *Yes* still works if
your plans change. No reason is asked or stored. *Delete* removes an absence again.

**Calendar subscription** (*Family* → *Calendar subscription*): *Create link* shows a private link once; copy it into your phone's
calendar or Google/Apple Calendar ("subscribe to calendar" / "add from URL"). It then shows your and your children's
events, four weeks back to six months ahead, including place and notes, and updates by itself every few hours.
Anyone with the link sees your schedule, so keep it to yourself. *Create a new link* replaces it (the old one stops
working) and *Revoke link* ends it.

**My fees** (*Family* → *Fees*; the list shows how many are still open) lists your membership fees with amount, due date and whether they are paid (and your children's, if you
are a guardian). For an open fee, *QR bill (PDF)* downloads an A4 page with the Swiss QR bill to pay with your banking
app (shown once the treasurer has entered the club's payment details). Your address is not printed on it. The fee
shows as paid only after the treasurer has seen the payment. Questions about a fee go to the club's treasurer.

**My profile** (*Family* → *My profile*) shows what the club stores about you: name, date of birth, gender (if recorded), whether you play,
your role and your teams. Change your *Email* and *Phone* there and choose *Save contacts*; adults need both. Other
details are changed by the club administration. If the club changed your record while the page was open, nothing is
saved and the current values are shown, so you can check and save again. Untick *Show my name on the public member
list* to take your name off the list anyone can see; tick it to show it again.

## For guardians

If the club recorded you as a guardian, *My children* appears on *Family* with your children's first names, and your children's events appear on *Start*
and *Schedule*.

- *Open* shows the child's date of birth, whether they play and their teams.
- Change the child's *Email* and *Phone* and choose *Save contacts*. Clearing a field removes it. Nothing else about
  the child, and nothing about other members, can be seen or changed here.
- *Start* and *Schedule* also list the trainings and events of the child's teams (*Is Lea coming?*), and you answer
  for the child there; on *Schedule* the child's button shows only their events.
- *My fees* also lists the child's membership fees, marked *for* and the child's first name.
- If the child reaches the club's adult age, or the club removes you as guardian, the child disappears from the list
  and from your schedule.

## Getting help

Problems with signing in, or a record you cannot change: ask a club administrator. Server problems, permanent deletion
and recovery are for the operator ([runbook](deployment/infomaniak-demo.md)).
