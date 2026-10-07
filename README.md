# Presence Consensus

Presence Consensus is a local Hubitat driver that combines several imperfect occupancy signals into one stable household presence result. Instead of trusting a single phone, motion sensor or network check, automations report evidence to this virtual device. Weighted thresholds, report expiration, a minimum-present hold and away confirmation reduce false departures and rapid state changes.

It makes no network requests and needs no account, API key, library or companion service.

## Why use consensus?

Presence sources fail in different ways:

- A phone may briefly leave Wi-Fi while someone is still home.
- Motion can be quiet while occupants sleep or watch a movie.
- A door opening indicates activity but does not prove whether someone arrived or left.
- A geofence may update late.
- A local network probe can miss a sleeping device.

Presence Consensus lets strong signals carry more weight, lets short-lived clues expire, and requires enough agreement before changing the standard Hubitat presence value.

## Install

1. In Hubitat, open **Drivers Code → New Driver**.
2. Import the [raw driver](https://raw.githubusercontent.com/b69ca/hubitat-presenceconsensus/main/PresenceConsensus.groovy) and click **Save**.
3. Open **Devices → Add Device → Virtual**.
4. Select **Presence Consensus** and name the device.
5. Configure evidence sources and thresholds.
6. Click **Save Preferences**.

## Evidence source format

Enter one source per line:

```text
Name | weight | expiry minutes
```

Example:

```text
Phone presence | 3 | 15
Recent motion | 1 | 10
Arrival routine | 2 | 30
```

Each field has a specific purpose:

| Field | Range | Meaning |
| --- | --- | --- |
| Name | Up to 60 characters | Name passed to report commands. Matching ignores capitalization and repeated spaces. |
| Weight | 1–10 | Strength contributed to the present or absent score. |
| Expiry | 1–1,440 minutes | How long the report remains active without another report from that source. |

Up to twelve sources can be configured. The present and absent thresholds cannot exceed the total configured weight.

## How decisions work

Each active report contributes its configured weight to either the present score or absent score.

- Present threshold met, with a stronger score than any simultaneous absent consensus: `present`.
- Absent threshold met, with a stronger score than any simultaneous present consensus: the away stability checks begin.
- Neither threshold met, or both sides meet their thresholds with equal scores: `uncertain`.

Present applies immediately. Before absent can apply:

1. The minimum-present period must have ended.
2. The absent score must remain decisive for the configured confirmation period.

When evidence is uncertain, the standard `presence` attribute retains its last resolved value. `decisionState` exposes the uncertainty so automations can treat it separately.

## Decision states

| State | Meaning |
| --- | --- |
| `setup` | Preferences have not been saved. |
| `present` | Present evidence reached a decisive score. |
| `absent` | Absent evidence passed the hold and confirmation checks. |
| `uncertain` | No side has a decisive score, or decisive scores are tied. |
| `holding` | Absent evidence is decisive, but the minimum-present period is active. |
| `confirming` | Absent evidence is decisive and must remain so for the confirmation period. |
| `overridePresent` | A timed manual present override is active. |
| `overrideAbsent` | A timed manual absent override is active. |
| `error` | Source syntax or score thresholds are invalid. |

## Commands

| Command | Purpose |
| --- | --- |
| **Report Present** | Records present evidence for one configured source. |
| **Report Absent** | Records absent evidence for one configured source. |
| **Clear Report** | Removes one source's current evidence. |
| **Clear All Reports** | Removes all current evidence without changing transition history. |
| **Force Present** | Overrides the result as present for 1–1,440 minutes. |
| **Force Absent** | Overrides the result as absent for 1–1,440 minutes. |
| **Clear Override** | Returns immediately to the evidence-based result. |
| **Refresh** | Re-evaluates scores, expiration and timers immediately. |
| **Initialize** | Rebuilds the expiration schedule while retaining configured reports. |

When only one source is configured, report and clear commands accept a blank source argument. With multiple sources, pass a configured name. Unknown names are rejected and appear in `lastError`; they never create accidental sources.

Reports continue aging during an override. Clearing or reaching the override deadline immediately reconciles the evidence that remains active.

## Automation events

| Button | Event |
| --- | --- |
| 1 | Standard presence resolved to present. |
| 2 | Standard presence resolved to absent. |
| 3 | Evidence entered an uncertain, holding or confirming state. |

Button 3 is disabled by default because uncertainty can be routine as evidence expires. Each button has a test command. Test events start with `TEST:` and do not modify reports or presence. The standard **Push** command accepts button 1, 2 or 3 and invokes the matching test event.

`notificationText` provides a ready-to-send message and `notificationType` identifies `present`, `absent`, `uncertain` or `test`.

## Preferences

| Preference | Default | Purpose |
| --- | --- | --- |
| Evidence sources | Three example sources | Names, weights and expiration periods. |
| Present score required | 3 | Active present weight needed for a present decision. |
| Absent score required | 3 | Active absent weight needed before away stability checks. |
| Initial presence | Present | Standard capability value before the first decisive consensus. |
| Absent confirmation | 2 minutes | Time absent consensus must remain continuous. |
| Minimum present time | 5 minutes | Prevents an immediate departure after decisive present evidence. |
| Expiration check | 1 minute | Scheduled reevaluation interval. Commands always evaluate immediately. |
| Present event | On | Emits button 1 after a resolved transition to present. |
| Absent event | On | Emits button 2 after a resolved transition to absent. |
| Uncertain event | Off | Emits button 3 when evidence first becomes non-decisive. |
| Debug logging | Off | Automatically returns to off after 30 minutes. |

## Attributes

### Result

| Attribute | Meaning |
| --- | --- |
| `presence` | Standard Hubitat `present` or `not present` value. |
| `decisionState` | Detailed state from the table above. |
| `conditionSummary` | Compact result and score summary. |
| `secondsToNextChange` | Earliest report expiry, hold end, confirmation end or override end. |
| `lastTransition` | Most recent resolved presence transition. |
| `presentTransitions`, `absentTransitions` | Resolved transitions since installation. |

### Evidence

| Attribute | Meaning |
| --- | --- |
| `presentScore`, `absentScore` | Current active evidence totals. |
| `presentThreshold`, `absentThreshold` | Configured score requirements. |
| `activeReports` | Number of unexpired source reports. |
| `configuredSources` | Number of configured sources. |
| `evidenceCoverage` | Percentage of configured weight with an active report. |
| `sourceStatus` | Per-source report, weight and remaining lifetime. |
| `staleSources` | Sources whose latest reports have expired. |
| `lastReport`, `lastReportSource`, `lastReportState` | Most recent accepted report or clear action. |
| `lastEvaluated` | Latest consensus calculation. |
| `lastError` | Configuration or rejected-command detail. |
| `overrideUntil`, `overrideUntilEpoch` | Active override deadline. |

## Rule Machine examples

### Phone presence

Create two rules for the phone's normal presence device:

- When it becomes present, call `reportPresent("Phone presence")`.
- When it becomes not present, call `reportAbsent("Phone presence")`.

Give this source more weight than a brief motion or door clue, but combine it with another source before declaring the home away.

### Recent activity

When selected motion sensors become active, call `reportPresent("Recent motion")`. Its report naturally expires after the configured time, so no inactive report is required.

### Strong away evidence

A rule that verifies every family phone is away and the entry door was closed can call `reportAbsent("All phones away")`. Give that verified result enough weight to reach the absent threshold, then use confirmation time to reject a short network or geofence interruption.

### Pause away automations

Before guests, contractors or house sitters arrive, call **Force Present** for the expected duration. Reports continue updating underneath the override, and the driver resumes ordinary consensus automatically.

## Maker API

Expose the virtual device through Hubitat Maker API, then call `reportPresent` or `reportAbsent` from a trusted local system. Pass the source name as the command argument and URL-encode spaces and punctuation.

Protect the Maker API access token as a credential and keep it out of public logs and repositories. Presence Consensus itself never stores or transmits that token.

## Design guidance

- Give independent sources weight. Two automations derived from the same phone are usually one underlying signal, not two independent confirmations.
- Set expiration longer than the source's normal reporting interval and expected scheduling jitter.
- Motion is strong present evidence but weak absent evidence. Let a present motion report expire instead of reporting absent when motion becomes inactive.
- Network absence is often less reliable than network presence. Use confirmation time and combine it with another source.
- Keep the absent threshold high enough that one unreliable integration cannot run whole-house away routines.
- Use `decisionState` when an automation needs to distinguish a resolved result from a retained value during uncertainty.

## Development

Run the simulated Hubitat suite with Groovy 2.4.21:

```sh
groovy tests/PresenceConsensusTest.groovy
```

The suite covers safe installation, weighted decisions, conflicting scores, hold and confirmation timing, per-source expiration, standard presence transitions, timed overrides, unknown sources, test events, single-source shorthand, invalid configurations and JSON-safe state. GitHub Actions runs the same suite.

Source is available under the MIT License.
