// Run from the repository root with Groovy 2.4.21.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def state = [:]
def settings = [
  sources:'Phone | 3 | 15\nMotion | 1 | 10\nWi-Fi | 2 | 8',
  presentScoreRequired:3, absentScoreRequired:3, initialPresence:'present',
  absentConfirmationMinutes:2, minimumPresentMinutes:5, evaluationMinutes:'1',
  emitPresentEvent:true, emitAbsentEvent:true, emitUncertainEvent:true, logEnable:false
]
def readings = [:]
def events = []
def jobs = [:]
long clock = Date.parse('yyyy-MM-dd HH:mm', '2026-10-07 18:00', TimeZone.getTimeZone('UTC')).time
def location = [timeZone:TimeZone.getTimeZone('America/Denver')]

def binding = new Binding([
  state:state, settings:settings, location:location,
  metadata:{ Closure ignored -> }, now:{ -> clock },
  device:[currentValue:{ String name -> readings[name] }, updateSetting:{ String name, Map value -> settings[name] = value.value }],
  log:[warn:{ ignored -> }, debug:{ ignored -> }, info:{ ignored -> }],
  sendEvent:{ Map event -> events << event; readings[event.name] = event.value },
  runIn:{ seconds, handler, Map options = [:] -> jobs[handler] = [seconds:seconds, options:options] },
  unschedule:{ String handler = null -> if (handler) jobs.remove(handler) else jobs.clear() },
  schedule:{ expression, handler -> jobs[handler] = [cron:expression] }
])

def driver = new GroovyShell(binding).parse(new File('PresenceConsensus.groovy'))
driver.run()

driver.installed()
assert readings.decisionState == 'setup'
assert readings.presence == 'present'
assert readings.numberOfButtons == 3
assert readings.configuredSources == 0
assert events.findAll { it.name == 'pushed' }.empty
println 'PASS: installation is inert and publishes a safe setup state'

driver.updated()
assert readings.decisionState == 'uncertain'
assert readings.presence == 'present'
assert readings.configuredSources == 3
assert readings.presentThreshold == 3
assert readings.absentThreshold == 3
assert readings.activeReports == 0
assert readings.evidenceCoverage == 0
assert jobs.evaluateConsensus.cron == '17 */1 * ? * *'
assert events.findAll { it.name == 'pushed' }.empty
println 'PASS: valid configuration schedules expiration checks and retains initial presence while uncertain'

driver.reportPresent('pHoNe')
assert readings.decisionState == 'present'
assert readings.presence == 'present'
assert readings.presentScore == 3
assert readings.activeReports == 1
assert readings.evidenceCoverage == 50
assert readings.lastReportSource == 'Phone'
assert readings.sourceStatus.contains('Phone: PRESENT +3')
assert state.lastPresentEpoch == clock
println 'PASS: case-insensitive weighted present evidence reaches consensus immediately'

clock += 60_000L
driver.reportAbsent('Wi-Fi')
assert readings.decisionState == 'present' // Present meets its threshold; absent does not yet.
assert readings.presence == 'present'
assert readings.presentScore == 3
assert readings.absentScore == 2
println 'PASS: sub-threshold conflicting evidence does not overturn a decisive result'

clock += 60_000L
driver.reportAbsent('Motion')
assert readings.decisionState == 'uncertain' // Scores are tied at 3.
assert readings.absentScore == 3
assert events.findAll { it.name == 'pushed' }[-1].value == 3
assert readings.notificationType == 'uncertain'
driver.clearReport('Phone')
assert readings.decisionState == 'holding'
assert readings.presence == 'present'
assert readings.presentScore == 0
assert readings.absentScore == 3
assert readings.secondsToNextChange == 240L
println 'PASS: equal scores become uncertain and the minimum-present hold blocks a premature departure'

clock += 4 * 60_000L
driver.evaluateConsensus()
assert readings.decisionState == 'confirming'
assert state.pendingAbsentSince == clock
assert readings.presence == 'present'
clock += 60_000L
driver.evaluateConsensus()
assert readings.decisionState == 'confirming'
clock += 60_000L
driver.evaluateConsensus()
assert readings.decisionState == 'absent'
assert readings.presence == 'not present'
assert readings.absentTransitions == 1
assert events.findAll { it.name == 'pushed' }[-1].value == 2
assert readings.notificationType == 'absent'
println 'PASS: continuous absent consensus respects hold and confirmation delays before departure'

clock += 60_000L
driver.reportPresent('Phone')
assert readings.decisionState == 'present'
assert readings.presence == 'present'
assert readings.presentTransitions == 1
assert events.findAll { it.name == 'pushed' }[-1].value == 1
assert readings.notificationType == 'present'
println 'PASS: decisive present evidence returns immediately and emits the arrival event'

driver.evaluateConsensus()
assert readings.absentScore == 1 // Wi-Fi has expired; Motion remains active.
assert readings.staleSources.contains('Wi-Fi')
clock += 3 * 60_000L
driver.evaluateConsensus()
assert readings.absentScore == 0
assert readings.staleSources.contains('Motion')
assert readings.decisionState == 'present' // Phone is still active.
clock += 12 * 60_000L
driver.evaluateConsensus()
assert readings.decisionState == 'uncertain'
assert readings.presence == 'present'
assert readings.activeReports == 0
assert readings.staleSources.contains('Phone')
println 'PASS: reports expire independently and stale evidence is excluded from scores'

driver.forceAbsent(10)
assert readings.decisionState == 'overrideAbsent'
assert readings.presence == 'not present'
assert readings.overrideUntilEpoch == clock + 600_000L
driver.reportPresent('Phone')
assert readings.decisionState == 'overrideAbsent'
assert readings.presentScore == 3
clock += 9 * 60_000L
driver.evaluateConsensus()
assert readings.decisionState == 'overrideAbsent'
clock += 60_000L
driver.evaluateConsensus()
assert readings.overrideUntilEpoch == 0
assert readings.decisionState == 'present' // Reports continue aging normally during an override.
driver.forcePresent(5)
assert readings.decisionState == 'overridePresent'
assert readings.presence == 'present'
driver.clearReport('Phone')
assert readings.decisionState == 'overridePresent'
driver.clearOverride()
assert readings.decisionState == 'uncertain'
assert readings.presence == 'present'
println 'PASS: timed present and absent overrides expire cleanly while reports continue aging'

driver.reportPresent('Unknown source')
assert readings.lastError.contains('Unknown source')
def pushedBeforeTests = events.count { it.name == 'pushed' }
driver.testPresentEvent(); assert events.findAll { it.name == 'pushed' }[-1].value == 1
driver.testAbsentEvent(); assert events.findAll { it.name == 'pushed' }[-1].value == 2
driver.testUncertainEvent(); assert events.findAll { it.name == 'pushed' }[-1].value == 3
assert events.count { it.name == 'pushed' } == pushedBeforeTests + 3
assert readings.notificationText.startsWith('TEST:')
assert readings.notificationType == 'test'
driver.push(2)
assert events.findAll { it.name == 'pushed' }[-1].value == 2
driver.push(9)
assert readings.lastError.contains('Button number')
println 'PASS: unknown sources are rejected and test events do not alter evidence'

settings.sources = 'Only source | 1 | 5'
settings.presentScoreRequired = 1
settings.absentScoreRequired = 1
settings.minimumPresentMinutes = 0
settings.absentConfirmationMinutes = 0
driver.updated()
driver.reportAbsent('')
assert readings.lastReportSource == 'Only source'
assert readings.decisionState == 'absent'
driver.clearAllReports()
assert readings.lastReportSource == 'All sources'
assert readings.activeReports == 0
assert readings.decisionState == 'uncertain'
println 'PASS: a blank source targets a sole configured source and all reports can be cleared'

settings.sources = 'Bad row'
driver.updated()
assert readings.decisionState == 'error'
assert readings.lastError.contains('Name | weight | expiry minutes')
settings.sources = 'One | 11 | 5'
driver.updated()
assert readings.decisionState == 'error'
assert readings.lastError.contains('Weight for One')
settings.sources = 'One | 1 | 5\none | 1 | 10'
driver.updated()
assert readings.decisionState == 'error'
assert readings.lastError.contains('unique')
settings.sources = (1..13).collect { "Source ${it} | 1 | 5" }.join('\n')
driver.updated()
assert readings.decisionState == 'error'
assert readings.lastError.contains('no more than 12')
settings.sources = 'One | 2 | 5'
settings.presentScoreRequired = 3
settings.absentScoreRequired = 1
driver.updated()
assert readings.decisionState == 'error'
assert readings.lastError.contains('exceeds the configured weight total')
assert !jobs.containsKey('evaluateConsensus')
println 'PASS: malformed, duplicate, excessive and impossible configurations fail safely'

def restored = new JsonSlurper().parseText(JsonOutput.toJson(state))
assert restored instanceof Map
assert JsonOutput.toJson(state).length() < 10000
println 'PASS: persisted evidence remains compact and JSON-safe'
println 'ALL CHECKS PASSED'
