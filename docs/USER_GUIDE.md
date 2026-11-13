# Sunloom user guide

Sunloom has five sections: **Home**, **Reserve**, **Sources**, **Schedule** and **Storage & About**. Switch between
them with the bar at the bottom of a narrow window, or the rail at the side of a wide one (Ctrl+1 to Ctrl+5 from the
keyboard).

## First run

Setup is four short steps, and **Start with defaults** on the first one finishes everything:

1. **Welcome**: what Sunloom does and where its pictures come from.
2. **When**: whether it changes by itself, the schedule, and your city (guessed from your time zone; change it with one
   click).
3. **What**: the kinds of picture, and whether the wallpaper is set for Home and Lock screen or just one.
4. **Preview and start**: a light and a dark example. On the GitHub build you can also opt in to a weekly check for a
   new version when you open the app.

You can run setup again any time from **Schedule → Setup Guide**. Nothing is saved until you finish it.

## Home

The picture on your wallpaper now. Under it:

- **Next** and **Previous** step through your Reserve. (Keys: N, P.)
- **Restore** puts back the wallpaper you had before Sunloom.
- **Dislike** removes the picture for good and chooses another. You get a few seconds to **Undo** before it is final.
- **⋮ More** opens *About this image* (who made it, its licence, where it came from), *Copy attribution* and *Save as…*.
  Right-clicking the picture opens the same menu.
- The bar at the top says what Sunloom just did, with **Pause / Resume** for the schedule. A long message opens when you
  tap it. If a fetch fails, **Try again** is there. After you add a photo, **Apply now** is.

Ctrl+plus, Ctrl+minus and Ctrl+0 zoom the preview. F11 goes full screen.

## Reserve

Every picture Sunloom may use, light and dark. Choose **List** or **Grid**.

- **Keep** a picture you love: it is never removed to make room, and shows more often.
- **Remove** takes it out of the Reserve (it may be fetched again later). **Dislike** is permanent.
- **Apply wallpaper now** sets it right away.
- Select several with Ctrl-click, Shift-click or Space, then Keep or Remove them together. Both can be undone.
- **Add Photo** adds one of your own. Sunloom checks it like any other picture, keeps it as you gave it, and never
  removes it by itself. **Restore Photo** sets the picture you want to go back to when you press Restore.

## Sources

Switch each source and each kind of picture on or off. NASA is off to begin with: its library is large and mixed, and
Sunloom filters it strictly. Under the sources are two switches: *Unmetered Wi-Fi or Ethernet only* (on) and *Pause
downloads on Battery Saver*. **Sync Catalog Now** asks the sources for new pictures.

## Schedule

- **Automatic switching** turns the schedule on and off.
- **Sunrise & sunset**: light by day, dark in the evening, for the city you pick (or type coordinates under *Custom
  coordinates*).
- **Follow system theme**: light when your device is light, dark when it is dark.
- **Custom schedule**: you choose the hours.
- **Manual only**: it changes only when you press Next.
- **Change every**: off to begin with. Choose 3, 6 or 12 hours and Sunloom also changes the wallpaper during the day and
  the evening, about that often, not only when they begin. The time is approximate, and the wait starts over whenever the
  wallpaper changes, and when you switch automatic switching back on. It does nothing while automatic switching is off,
  paused or the schedule is Manual. Pictures you have not
  seen yet come first; if there are none (for example on a metered connection), a picture shown least recently is reused.
- **Daytime wallpaper** and **Evening wallpaper**: Automatic until you choose. To choose, open a picture's menu (right-click,
  or the Menu key) in the Reserve or on Today and pick *Use as daytime wallpaper* or *Use as evening wallpaper*. If it is
  that period now, it is shown at once; otherwise it is shown when that period starts. It stays until the next period, so
  Next, Previous and a change in the system settings hold. It works only while automatic switching is on and the schedule
  is not Manual. A picture too small for your display is shown with a blurred background and can look soft. Press
  *Clear* to go back to Automatic. Disliking or removing the picture also clears it. After restoring a backup the
  picture file may be missing; the card then says *Not available* and the usual choice is used.
- **Displays**: what Sunloom found, and how one picture is fitted to all of them. *One wallpaper for all displays* is
  recommended. *Blurred frame fill* lets smaller pictures be used with a darkened blurred backdrop.
- **Home & Lock screen**, **Home screen only** or **Lock screen only**.
- **Use the system wallpaper** gives your device's default wallpaper back.

## Storage & About

- **About Sunloom**: version, build, licence, privacy policy, source code, open-source licences, **Check for updates**
  (GitHub build).
- **Single-key shortcuts**: switch N, P, K and Delete on or off. Ctrl shortcuts always work.
- **Storage**: how much room the Reserve takes, and the limit you choose (100 MB to 1 GB).
- **Reset Sunloom**: erases everything Sunloom keeps and starts setup again. It cannot be undone.
- **Report a problem** and **Copy diagnostics**.
- **Technical details**: why each picture was approved or skipped, for bug reports.

## Your data

Everything is on your device (see the [privacy policy](PRIVACY_POLICY.md)). Your settings and the list of pictures,
Keeps and Dislikes may be backed up by Android; the pictures themselves and photos you added are not, so keep the
originals of photos you care about.

## Keyboard

| Keys | Does |
|---|---|
| N / P | Next / previous wallpaper (can be turned off) |
| K | Keep or unkeep the current wallpaper (can be turned off) |
| Delete | Dislike the current wallpaper, with Undo (can be turned off) |
| Ctrl+1 … Ctrl+5 | Go to a section |
| Ctrl+Z | Undo the last Dislike or Remove |
| Ctrl+C / Ctrl+S | Copy attribution / Save as… (for the current wallpaper) |
| Ctrl+, | Open Schedule |
| F11 | Full screen |
| Esc | Leave a field or full screen |
