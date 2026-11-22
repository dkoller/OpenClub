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
| *Team* | Only for coaches and team managers (`VIEW_TEAMS` or `MANAGE_TEAMS`): *Next events* with who has not answered, *Helpers missing* (for managers), then teams, rosters, schedules and statistics |
| *Administration* | Only with an administration permission: *Waiting for you* (open join requests, helper shifts that still need people, unpaid fees), *Members*, *Join requests*, *Fees*, *Permissions* and *Settings*, each shown only to people who may use it |

Every page has its own address, so the browser's back button and bookmarks work (for example `#/schedule`). On a
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
- **Archive:** *Archive*, then *Confirm archive*. Archived members disappear from lists and lose their permissions,
  team places and guardianships. Only members with the *Member* role and no linked sign-in can be archived: unlink
  their sign-in first (see *Invitations*). Right after archiving, *Undo archive* brings them back.
- **Archived members:** this panel lists everyone archived earlier with the date. *Reactivate* makes them active
  again, **without** their former permissions, teams or sign-in. If the record no longer meets the club's rules (for
  example an adult without a phone number), reactivation is refused.
- Deleting a member for good is done by the server operator, not in the app.
- **Excel:** in *Excel import and export*, *Download member list (CSV for Excel)* saves all active members as a file
  Excel opens directly (phone numbers start with an apostrophe so Excel does not treat them as formulas). To import,
  save your sheet in Excel with *Save As* → *CSV UTF-8*; it needs the columns First name, Last name and Date of birth
  (31.12.2010), and may have Email, Phone, Gender and Player (yes/no), with headings in any of the four languages.
  *Check file* shows every row: *will be imported*, *already exists* (same name and birth date; left out) or *has
  problems* with the reason (left out). Nothing is created until you choose *Import N members*. Existing members are
  never changed. Every export and import is recorded.

### Teams (*Team* page, panels *Teams* and *Team members*)

- *Team name* and *Add team* create a team; *Rename* and *Delete team* (type its name to confirm) change it. Deleting
  a team also deletes its schedule.
- *Schedule* under a team shows and plans its trainings and events (see *For team managers*).
- Open a team to see *Team members*. Find a member and *Assign*; *Remove* takes them out of the team.
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

*Manage club roles* switches a member between *Member* and *Administrator*. The last administrator cannot be demoted.

### Invitations (*Administration* → *Members*, panel *Invitations and account links*)

1. Find the member and choose *Create invitation*.
2. Copy the *Invitation link (shown only once)* and send it to the member through a channel you trust. It is valid for
   seven days, works once and only together with the member's own sign-in.
3. *Revoke invitation* cancels an open link. *Unlink account* disconnects a member's sign-in and ends their session at
   once; it needs `MANAGE_PERMISSIONS` **and** the *Administrator* role.

Minors and archived members cannot be invited, and an administrator's sign-in cannot be unlinked here.

### Guardians (*Administration* → *Members*, panel *Guardians*)

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

### Club settings (*Administration* → *Settings*, permission `MANAGE_PERMISSIONS`)

- *Edit club name*.
- *Adult age*: from which age members count as adults (13 to 25, default 18). Adults need email and phone, so raising
  the number is refused while members who would become adults lack them.
- *Player gender rule*: *Require gender for players*. Turning it on is refused while players without a gender exist.

## For team managers

**Next events.** The *Team* page starts with the next 14 days of your teams' events (at most eight teams are
included), each with its totals and how many have not answered yet (*11 yes · 2 no · 3 open*). Open one to see *Who is
coming?*: the totals side by side, then the names under *No answer yet*, *No*, *Maybe* and *Yes* (adults by full name,
minors by first name and initial). Absences count as *No*; no reason is ever shown. The same page lists the event's
helper shifts and rides.

**Schedule.** On the *Team* page, choose *Schedule* under a team to see its next eight weeks. With `MANAGE_TEAMS` for the
team you add events: *Kind* (Training, Match or Event), *Title*, *Date*, *Start*, *End*, optionally *Place* and
*Notes*, then *Add event*. Fill *Repeat weekly until* to create the same event every week up to that date (at most
52). *Edit* changes one date, *Cancel event* marks it cancelled (members still see it, marked *Cancelled*;
*Reinstate* undoes this) and *Delete* followed by *Confirm delete* removes it. With `VIEW_TEAMS` you see the
schedule without these buttons. Each event shows the totals (*Answers: 5 Yes, 1 No, 2 Maybe*); *Show answers* lists
every team member's answer, or *no answer*, by first and last name (minors by first name and initial). Times are entered and shown in your device's time zone; weekly repeats keep the same
local time across the change to and from summer time.

**Attendance statistics.** With `VIEW_TEAMS` or `MANAGE_TEAMS` for a team, *Attendance statistics* in its schedule
shows, for the last 12 months (change *From* and *To*, at most a year, then *Show*), how many events took place and
how each member answered them: *Yes*, *No*, *Maybe* and *No answer*, with totals. Cancelled events do not count.
Answers are deleted 12 months after an event, and members who joined later show earlier events as *No answer*.

**Helpers missing.** With `MANAGE_TEAMS`, the *Team* page starts with the helper shifts of your teams in the next 14
days that still have free places (*Bar: 2 of 4 places free*), with the event, team and time. Only the numbers are
shown, not who signed up. A club-wide manager also sees them on *Waiting for you*. Seven days before such a shift,
the team and the parents of its children are also asked by email (*Helpers wanted*, if the club sends email).

**Volunteer shifts.** An event's page (from *Next events*), or *Show shifts and rides* under an event in the team schedule, lists its helper jobs (for example *Bar*, 14:00–16:00,
*2 of 3 filled*) with the names of who signed up. With `MANAGE_TEAMS` for the team you add a shift: *Task*, *Date*,
*Start*, *End* (prefilled with the event's times, at most 24 hours and within a day of the event) and *People needed*,
then *Add shift*. *Edit* changes a shift (you cannot lower the places below the people already signed up), *Delete*
followed by the confirmation removes it with its sign-ups.

With `MANAGE_TEAMS` for a team you manage its assignments and can open *Contact details* of its **active** members
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
choosing your answer again clears it. You see the totals, but never other members' answers.

**One event.** Tap an event (or *Rides, helpers and details*) to open its page: when and where, notes, the answers for
you and your children, the team's totals, helper shifts and rides. *Sign up* puts you (or your child) on a
shift while places are free, *Withdraw* takes you off, both until the shift starts. Your team sees your name on the
shift so everyone can coordinate.

**Email notifications** (*Family* → *Email notifications*): if your club sends email, you get one when an event of your team (or your
child's team) is changed or cancelled, when new events are added, when an announcement is posted for you, and a
reminder the day before a volunteer shift you signed up for. *Helpers wanted*: seven days before a helper shift of
your team (or your child's) that still has free places, you get one email per event listing those shifts with the
free places and a link; not if you (or your child) already help at that event. Untick what you do not want and choose the language of
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
are a guardian). Questions about a fee go to the club's treasurer.

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
