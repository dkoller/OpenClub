# OpenClub user guide

For the people who use a club's OpenClub: administrators, team managers, members and guardians. Button and field names
are the English ones; switch the language (German, English, French, Italian) with *Language* at the top of the page.

> **Fictional data only.** OpenClub is in a synthetic-data test phase. Do not enter real people's names, birth dates,
> addresses or phone numbers until the club's privacy approval is complete (see the [README](../README.md)).

What you see depends on your permissions: panels you cannot use are not shown at all. Every action is checked by the
server. Every change to member data and every read of someone's private details is also recorded in an audit trail
that holds identifiers only (team schedules hold no personal data and are not recorded).

## Signing in

Choose *Sign in*. You are sent to the club's sign-in service (Keycloak or Rauthy), then back to the club page.

- *Signed in and authorized for this club.* You are a member; the panels you may use appear below.
- *This account is not yet a club member.* Your account works, but no member record is linked to it. Ask the club for
  an invitation, or paste one you received into *Accept an invitation*.

*Sign out* ends your session on this device.

## For administrators

Administrators are members who hold permissions. Permissions are separate from the *Administrator* role: the role
alone allows nothing, and each panel needs its permission (see *Permissions* below).

### Members (*Manage members*, permission `EDIT_MEMBERS`)

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
  phone number), nothing is saved and you are asked to edit again, so their change is not overwritten.
- **Archive:** *Archive*, then *Confirm archive*. Archived members disappear from lists and lose their permissions,
  team places and guardianships. Only members with the *Member* role and no linked sign-in can be archived: unlink
  their sign-in first (see *Invitations*). Right after archiving, *Undo archive* brings them back.
- **Archived members:** this panel lists everyone archived earlier with the date. *Reactivate* makes them active
  again, **without** their former permissions, teams or sign-in. If the record no longer meets the club's rules (for
  example an adult without a phone number), reactivation is refused.
- Deleting a member for good is done by the server operator, not in the app.

### Teams (*Teams*, *Team members*)

- *Team name* and *Add team* create a team; *Rename* and *Delete team* (type its name to confirm) change it. Deleting
  a team also deletes its schedule.
- *Schedule* under a team shows and plans its trainings and events (see *For team managers*).
- Open a team to see *Team members*. Find a member and *Assign*; *Remove* takes them out of the team.
- *Contact details* shows a team member's email and phone (see *For team managers*).

### Permissions (*Manage permissions*, permission `MANAGE_PERMISSIONS`)

Find a member, choose the *Scope* (*Club*, or one *Team*) and the *Capability*, then *Grant*. *Current grants* lists
what they hold; *Revoke* removes one. A change applies to the member's next action.

| Capability | Allows |
| --- | --- |
| `VIEW_MEMBER_DIRECTORY` | Searching members by name |
| `EDIT_MEMBERS` | Adding, editing, archiving, reactivating and exporting members; invitations; guardians |
| `VIEW_TEAMS` | Seeing teams, their members and their schedules (club-wide or one team) |
| `MANAGE_TEAMS` | Managing teams, assignments and schedules, and reading the contacts of active team members (club-wide or one team) |
| `MANAGE_PERMISSIONS` | Granting and revoking permissions; with the *Administrator* role also unlinking sign-ins |

*Manage club roles* switches a member between *Member* and *Administrator*. The last administrator cannot be demoted.

### Invitations (*Invitations and account links*)

1. Find the member and choose *Create invitation*.
2. Copy the *Invitation link (shown only once)* and send it to the member through a channel you trust. It is valid for
   seven days, works once and only together with the member's own sign-in.
3. *Revoke invitation* cancels an open link. *Unlink account* disconnects a member's sign-in and ends their session at
   once; it needs `MANAGE_PERMISSIONS` **and** the *Administrator* role.

Minors and archived members cannot be invited, and an administrator's sign-in cannot be unlinked here.

### Guardians (*Guardians*)

1. *Find a minor*, then *Search*; the minor's current guardians are listed.
2. *Find a guardian* (an adult member who already signs in), select them and choose *Add as guardian*. By doing so you
   confirm that you checked the relationship outside OpenClub; the app cannot check it.
3. *Remove* ends a guardianship. A minor has at most two guardians. Guardianships end on their own when the child
   reaches the club's adult age, or when either person is archived or the guardian's sign-in is unlinked.

Being a guardian gives no other permission in the club.

### Club settings

- *Edit club name*.
- *Adult age*: from which age members count as adults (13 to 25, default 18). Adults need email and phone, so raising
  the number is refused while members who would become adults lack them.
- *Player gender rule*: *Require gender for players*. Turning it on is refused while players without a gender exist.

## For team managers

**Schedule.** In *Teams*, choose *Schedule* under a team to see its next eight weeks. With `MANAGE_TEAMS` for the
team you add events: *Kind* (Training, Match or Event), *Title*, *Date*, *Start*, *End*, optionally *Place* and
*Notes*, then *Add event*. Fill *Repeat weekly until* to create the same event every week up to that date (at most
52). *Edit* changes one date, *Cancel event* marks it cancelled (members still see it, marked *Cancelled*;
*Reinstate* undoes this) and *Delete* followed by *Confirm delete* removes it. With `VIEW_TEAMS` you see the
schedule without these buttons. Times are entered and shown in your device's time zone; weekly repeats keep the same
local time across the change to and from summer time.

With `MANAGE_TEAMS` for a team you manage its assignments and can open *Contact details* of its **active** members
(name, email, phone, player). Members of other teams, archived members and everyone's birth date stay hidden. Each
contact read is recorded.

## For members

Once your invitation is accepted you are signed in as a club member. Without further permissions you see the club and
its public member list (adults by full name, minors by first name and surname initial, nothing private).

**My schedule** lists the next eight weeks of trainings, matches and events of your teams, and of your children's
teams if you are a guardian (marked *For* and the child's name).

**My profile** shows what the club stores about you: name, date of birth, gender (if recorded), whether you play,
your role and your teams. Change your *Email* and *Phone* there and choose *Save contacts*; adults need both. Other
details are changed by the club administration. If the club changed your record while the page was open, nothing is
saved and the current values are shown, so you can check and save again.

## For guardians

If the club recorded you as a guardian, *My children* appears after you sign in.

- *Open* shows the child's date of birth, whether they play and their teams.
- Change the child's *Email* and *Phone* and choose *Save contacts*. Clearing a field removes it. Nothing else about
  the child, and nothing about other members, can be seen or changed here.
- *My schedule* also lists the trainings and events of the child's teams, marked *For* and the child's first name.
- If the child reaches the club's adult age, or the club removes you as guardian, the child disappears from the list
  and from your schedule.

## Getting help

Problems with signing in, or a record you cannot change: ask a club administrator. Server problems, permanent deletion
and recovery are for the operator ([runbook](deployment/infomaniak-demo.md)).
