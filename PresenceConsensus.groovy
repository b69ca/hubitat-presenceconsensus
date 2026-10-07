import groovy.transform.Field

/*
 * Presence Consensus
 *
 * Combines expiring reports from several presence sources into one stable
 * household presence result for Hubitat automations.
 *
 * Author: Jon Wallace
 * Copyright 2026 Jon Wallace
 * License: MIT
 * Version: 1.0.0
 */

metadata {
  definition(
    name: "Presence Consensus",
    namespace: "jonw",
    author: "Jon Wallace",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/b69ca/hubitat-presenceconsensus/main/PresenceConsensus.groovy"
  ) {
    capability "Sensor"
    capability "PresenceSensor"
    capability "Refresh"
    capability "Initialize"
    capability "PushableButton"

    command "reportPresent", [[name:"Source name", type:"STRING", description:"Configured source; blank is allowed when only one source is configured"]]
    command "reportAbsent", [[name:"Source name", type:"STRING", description:"Configured source; blank is allowed when only one source is configured"]]
    command "clearReport", [[name:"Source name", type:"STRING", description:"Configured source whose report should be forgotten"]]
    command "clearAllReports"
    command "forcePresent", [[name:"Minutes", type:"NUMBER", description:"Override the consensus as present for 1 to 1440 minutes"]]
    command "forceAbsent", [[name:"Minutes", type:"NUMBER", description:"Override the consensus as absent for 1 to 1440 minutes"]]
    command "clearOverride"
    command "testPresentEvent"
    command "testAbsentEvent"
    command "testUncertainEvent"

    attribute "decisionState", "enum", ["setup", "present", "absent", "uncertain", "holding", "confirming", "overridePresent", "overrideAbsent", "error"]
    attribute "presentScore", "number"
    attribute "absentScore", "number"
    attribute "presentThreshold", "number"
    attribute "absentThreshold", "number"
    attribute "activeReports", "number"
    attribute "configuredSources", "number"
    attribute "evidenceCoverage", "number"
    attribute "sourceStatus", "string"
    attribute "staleSources", "string"
    attribute "secondsToNextChange", "number"
    attribute "lastReport", "string"
    attribute "lastReportSource", "string"
    attribute "lastReportState", "enum", ["present", "absent", "cleared"]
    attribute "lastEvaluated", "string"
    attribute "lastTransition", "string"
    attribute "presentTransitions", "number"
    attribute "absentTransitions", "number"
    attribute "overrideUntil", "string"
    attribute "overrideUntilEpoch", "number"
    attribute "conditionSummary", "string"
    attribute "lastError", "string"
    attribute "notificationText", "string"
    attribute "notificationType", "enum", ["present", "absent", "uncertain", "test"]
  }

  preferences {
    input name: "setupNotes", type: "paragraph", title: "Presence Consensus 1.0.0",
      description: "Combines local reports from phone presence, motion, doors, Wi-Fi checks and other automations. Each line uses Name | weight | expiry minutes. Reports are supplied by Rule Machine, Maker API, webCoRE or another local integration."
    input name: "sources", type: "textarea", title: "Evidence sources (Name | weight | expiry minutes)", required: true,
      defaultValue: "Phone presence | 3 | 15\nRecent motion | 1 | 10\nArrival routine | 2 | 30"
    input name: "presentScoreRequired", type: "number", title: "Score required to decide present", range: "1..120", defaultValue: 3
    input name: "absentScoreRequired", type: "number", title: "Score required to decide absent", range: "1..120", defaultValue: 3
    input name: "initialPresence", type: "enum", title: "Standard presence before the first decisive consensus", options: [
      "present":"Present (safer for occupied-home automations)", "absent":"Not present"], defaultValue: "present"

    input name: "stabilityNotes", type: "paragraph", title: "Stability controls",
      description: "Present consensus applies immediately. An absent result can be delayed and can also be blocked for a minimum time after a present decision. Uncertain evidence retains the last standard presence value."
    input name: "absentConfirmationMinutes", type: "number", title: "Require continuous absent consensus for this many minutes", range: "0..60", defaultValue: 2
    input name: "minimumPresentMinutes", type: "number", title: "Minimum time to remain present after a present decision", range: "0..1440", defaultValue: 5
    input name: "evaluationMinutes", type: "enum", title: "Evidence expiration check interval", options: [
      "1":"Every minute", "2":"Every 2 minutes", "5":"Every 5 minutes", "10":"Every 10 minutes"], defaultValue: "1"

    input name: "automationNotes", type: "paragraph", title: "Automation events",
      description: "Button 1: resolved present. Button 2: resolved absent. Button 3: evidence became uncertain. Test commands emit marked events without changing the consensus."
    input name: "emitPresentEvent", type: "bool", title: "Emit button 1 when presence resolves to present", defaultValue: true
    input name: "emitAbsentEvent", type: "bool", title: "Emit button 2 when presence resolves to absent", defaultValue: true
    input name: "emitUncertainEvent", type: "bool", title: "Emit button 3 when evidence first becomes uncertain", defaultValue: false
    input name: "logEnable", type: "bool", title: "Debug logging (automatically disables after 30 minutes)", defaultValue: false
  }
}

@Field static final int MAX_SOURCES = 12
@Field static final int NUMBER_OF_BUTTONS = 3

def installed() {
  unschedule()
  state.configured = false
  state.reports = [:]
  state.presentTransitions = 0
  state.absentTransitions = 0
  state.resolvedState = "present"
  publishButtonCount()
  sendChanged("decisionState", "setup")
  sendChanged("presence", "present")
  sendChanged("presentScore", 0)
  sendChanged("absentScore", 0)
  sendChanged("activeReports", 0)
  sendChanged("configuredSources", 0)
  sendChanged("evidenceCoverage", 0)
  sendChanged("presentTransitions", 0)
  sendChanged("absentTransitions", 0)
  sendChanged("secondsToNextChange", 0)
  sendChanged("conditionSummary", "Save preferences to start combining presence evidence")
}

def updated() {
  state.configured = true
  initializeConsensus("preferences saved")
}

def initialize() {
  if (state.configured != true) {
    publishButtonCount()
    sendChanged("decisionState", "setup")
    return
  }
  initializeConsensus("hub startup or initialize command")
}

def uninstalled() {
  unschedule()
}

private void initializeConsensus(String reason) {
  try {
    List configured = configuredSourceRecords()
    validateThresholds(configured)
    unschedule()
    retainConfiguredReports(configured)
    if (!(state.resolvedState in ["present", "absent"])) {
      state.resolvedState = enumSetting("initialPresence", "present", ["present", "absent"])
    }
    state.pendingAbsentSince = 0L
    state.overrideState = ""
    state.overrideUntilEpoch = 0L
    state.lastDecisionState = "setup"
    publishButtonCount()
    sendChanged("configuredSources", configured.size())
    sendChanged("presentThreshold", numberSetting("presentScoreRequired", 3, 1, 120))
    sendChanged("absentThreshold", numberSetting("absentScoreRequired", 3, 1, 120))
    sendChanged("overrideUntil", "")
    sendChanged("overrideUntilEpoch", 0)
    sendChanged("lastError", "")
    configureSchedule()
    if (settings?.logEnable) runIn(1800, "logsOff", [overwrite:true])
    evaluateConsensus()
    if (settings?.logEnable) log.debug "Presence Consensus initialized (${reason}) with ${configured.size()} source(s)"
  } catch (IllegalArgumentException e) {
    recordConfigurationError(e.message)
  }
}

private void configureSchedule() {
  int minutes = numberSetting("evaluationMinutes", 1, 1, 10)
  schedule("17 */${minutes} * ? * *", "evaluateConsensus")
}

def reportPresent(String sourceName = "") { recordReport(sourceName, "present") }
def reportAbsent(String sourceName = "") { recordReport(sourceName, "absent") }

private void recordReport(String sourceName, String reportState) {
  if (state.configured != true) {
    sendChanged("lastError", "Save preferences before reporting presence evidence.")
    return
  }
  try {
    List configured = configuredSourceRecords()
    Map source = resolveSource(sourceName, configured)
    long current = now()
    Map reports = state.reports instanceof Map ? state.reports : [:]
    reports[source.key] = [state:reportState, at:current]
    state.reports = reports
    sendChanged("lastReport", localText(current))
    sendChanged("lastReportSource", source.name)
    sendChanged("lastReportState", reportState)
    sendChanged("lastError", "")
    if (settings?.logEnable) log.debug "${source.name} reported ${reportState}"
    evaluateConsensus()
  } catch (IllegalArgumentException e) {
    rejectCommand(e.message)
  }
}

def clearReport(String sourceName) {
  if (state.configured != true) return
  try {
    List configured = configuredSourceRecords()
    Map source = resolveSource(sourceName, configured)
    Map reports = state.reports instanceof Map ? state.reports : [:]
    reports.remove(source.key)
    state.reports = reports
    sendChanged("lastReport", localText(now()))
    sendChanged("lastReportSource", source.name)
    sendChanged("lastReportState", "cleared")
    sendChanged("lastError", "")
    evaluateConsensus()
  } catch (IllegalArgumentException e) {
    rejectCommand(e.message)
  }
}

def clearAllReports() {
  state.reports = [:]
  state.pendingAbsentSince = 0L
  sendChanged("lastReport", localText(now()))
  sendChanged("lastReportSource", "All sources")
  sendChanged("lastReportState", "cleared")
  sendChanged("lastError", "")
  evaluateConsensus()
}

def forcePresent(BigDecimal minutes) { setOverride("present", minutes) }
def forceAbsent(BigDecimal minutes) { setOverride("absent", minutes) }

private void setOverride(String requested, BigDecimal minutes) {
  int duration = boundedMinutes(minutes)
  long until = now() + duration * 60000L
  state.overrideState = requested
  state.overrideUntilEpoch = until
  state.pendingAbsentSince = 0L
  sendChanged("overrideUntil", localText(until))
  sendChanged("overrideUntilEpoch", until)
  evaluateConsensus()
}

def clearOverride() {
  state.overrideState = ""
  state.overrideUntilEpoch = 0L
  state.pendingAbsentSince = 0L
  sendChanged("overrideUntil", "")
  sendChanged("overrideUntilEpoch", 0)
  evaluateConsensus()
}

def evaluateConsensus() {
  if (state.configured != true) return
  List configured
  try {
    configured = configuredSourceRecords()
    validateThresholds(configured)
  } catch (IllegalArgumentException e) {
    recordConfigurationError(e.message)
    return
  }

  long current = now()
  sendChanged("lastEvaluated", localText(current))
  List evidence = evidenceRecords(configured, current)
  int presentScore = evidence.findAll { it.active && it.reportState == "present" }.sum { it.weight } ?: 0
  int absentScore = evidence.findAll { it.active && it.reportState == "absent" }.sum { it.weight } ?: 0
  int activeWeight = evidence.findAll { it.active }.sum { it.weight } ?: 0
  int totalWeight = configured.sum { it.weight } ?: 0
  int presentRequired = numberSetting("presentScoreRequired", 3, 1, 120)
  int absentRequired = numberSetting("absentScoreRequired", 3, 1, 120)

  sendChanged("presentScore", presentScore)
  sendChanged("absentScore", absentScore)
  sendChanged("presentThreshold", presentRequired)
  sendChanged("absentThreshold", absentRequired)
  sendChanged("activeReports", evidence.count { it.active })
  sendChanged("evidenceCoverage", totalWeight ? Math.round(activeWeight * 100d / totalWeight) as Integer : 0)
  publishEvidenceText(evidence, current)

  String previousDecision = state.lastDecisionState ?: "setup"
  String decision
  long overrideUntil = (state.overrideUntilEpoch ?: 0L) as Long
  String override = state.overrideState?.toString()
  if (overrideUntil > current && override in ["present", "absent"]) {
    decision = override == "present" ? "overridePresent" : "overrideAbsent"
    applyResolvedState(override, current)
  } else {
    if (overrideUntil > 0L || override) {
      state.overrideState = ""
      state.overrideUntilEpoch = 0L
      sendChanged("overrideUntil", "")
      sendChanged("overrideUntilEpoch", 0)
    }
    String rawDecision = rawDecision(presentScore, absentScore, presentRequired, absentRequired)
    decision = applyDecision(rawDecision, current)
  }

  String resolved = state.resolvedState in ["present", "absent"] ? state.resolvedState : initialPresence()
  sendChanged("decisionState", decision)
  sendChanged("presence", resolved == "present" ? "present" : "not present")
  sendChanged("secondsToNextChange", secondsToNextChange(evidence, current))
  sendChanged("conditionSummary", conditionSummary(decision, resolved, presentScore, absentScore))
  emitDecisionEvents(decision, previousDecision, current)
  state.lastDecisionState = decision
}

private String rawDecision(int presentScore, int absentScore, int presentRequired, int absentRequired) {
  boolean presentMet = presentScore >= presentRequired
  boolean absentMet = absentScore >= absentRequired
  if (presentMet && (!absentMet || presentScore > absentScore)) return "present"
  if (absentMet && (!presentMet || absentScore > presentScore)) return "absent"
  return "uncertain"
}

private String applyDecision(String rawDecision, long current) {
  if (rawDecision == "present") {
    state.pendingAbsentSince = 0L
    applyResolvedState("present", current)
    return "present"
  }
  if (rawDecision == "absent") {
    long lastPresent = (state.lastPresentEpoch ?: 0L) as Long
    long holdUntil = lastPresent + numberSetting("minimumPresentMinutes", 5, 0, 1440) * 60000L
    if (state.resolvedState == "present" && current < holdUntil) {
      state.pendingAbsentSince = 0L
      return "holding"
    }
    int confirmation = numberSetting("absentConfirmationMinutes", 2, 0, 60)
    if (confirmation > 0) {
      long pending = (state.pendingAbsentSince ?: 0L) as Long
      if (pending <= 0L) {
        state.pendingAbsentSince = current
        return "confirming"
      }
      if (current - pending < confirmation * 60000L) return "confirming"
    }
    state.pendingAbsentSince = 0L
    applyResolvedState("absent", current)
    return "absent"
  }
  state.pendingAbsentSince = 0L
  return "uncertain"
}

private void applyResolvedState(String resolved, long current) {
  state.resolvedState = resolved
  if (resolved == "present") state.lastPresentEpoch = current
}

private void emitDecisionEvents(String decision, String previousDecision, long current) {
  String resolved = state.resolvedState
  String previousResolved = state.lastPublishedResolved
  if (previousResolved == null && resolved in ["present", "absent"]) {
    state.lastPublishedResolved = resolved
    return
  }
  if (resolved in ["present", "absent"] && resolved != previousResolved) {
    state.lastPublishedResolved = resolved
    sendChanged("lastTransition", localText(current))
    if (resolved == "present") {
      state.presentTransitions = ((state.presentTransitions ?: 0) as Integer) + 1
      sendChanged("presentTransitions", state.presentTransitions)
      if (settings?.emitPresentEvent != false && previousResolved != null) {
        emitNotification(1, "present", "Presence Consensus resolved present.")
      }
    } else {
      state.absentTransitions = ((state.absentTransitions ?: 0) as Integer) + 1
      sendChanged("absentTransitions", state.absentTransitions)
      if (settings?.emitAbsentEvent != false && previousResolved != null) {
        emitNotification(2, "absent", "Presence Consensus resolved absent.")
      }
    }
  }
  boolean uncertainNow = decision in ["uncertain", "holding", "confirming"]
  boolean uncertainBefore = previousDecision in ["uncertain", "holding", "confirming"]
  if (uncertainNow && !uncertainBefore && previousDecision != "setup" && settings?.emitUncertainEvent == true) {
    emitNotification(3, "uncertain", "Presence Consensus evidence is uncertain; standard presence remains ${resolved}.")
  }
}

private List evidenceRecords(List configured, long current) {
  Map reports = state.reports instanceof Map ? state.reports : [:]
  return configured.collect { source ->
    Map report = reports[source.key] instanceof Map ? reports[source.key] : [:]
    long at = (report.at ?: 0L) as Long
    long expires = at > 0L ? at + source.ttlMinutes * 60000L : 0L
    boolean active = at > 0L && expires > current && report.state in ["present", "absent"]
    [key:source.key, name:source.name, weight:source.weight, ttlMinutes:source.ttlMinutes,
      reportState:report.state ?: "none", reportedAt:at, expiresAt:expires, active:active]
  }
}

private void publishEvidenceText(List evidence, long current) {
  String status = evidence.collect { item ->
    if (!item.reportedAt) return "${item.name}: NONE"
    if (!item.active) return "${item.name}: STALE ${item.reportState.toString().toUpperCase()}"
    long remaining = Math.max(0L, Math.ceil(((item.expiresAt as Long) - current) / 60000d) as Long)
    return "${item.name}: ${item.reportState.toString().toUpperCase()} +${item.weight} (${remaining}m)"
  }.join(" · ")
  sendChanged("sourceStatus", safeText(status, 1024))
  sendChanged("staleSources", evidence.findAll { it.reportedAt && !it.active }.collect { it.name }.join(", "))
}

private long secondsToNextChange(List evidence, long current) {
  List deadlines = evidence.findAll { it.active && (it.expiresAt as Long) > current }.collect { it.expiresAt as Long }
  long overrideUntil = (state.overrideUntilEpoch ?: 0L) as Long
  if (overrideUntil > current) deadlines << overrideUntil
  long pending = (state.pendingAbsentSince ?: 0L) as Long
  int confirmation = numberSetting("absentConfirmationMinutes", 2, 0, 60)
  if (pending > 0L && confirmation > 0) deadlines << pending + confirmation * 60000L
  long lastPresent = (state.lastPresentEpoch ?: 0L) as Long
  long holdUntil = lastPresent + numberSetting("minimumPresentMinutes", 5, 0, 1440) * 60000L
  if (state.resolvedState == "present" && holdUntil > current) deadlines << holdUntil
  List future = deadlines.findAll { it > current }
  return future ? Math.max(0L, Math.ceil((future.min() - current) / 1000d) as Long) : 0L
}

private String conditionSummary(String decision, String resolved, int presentScore, int absentScore) {
  if (decision == "overridePresent") return "Override · present"
  if (decision == "overrideAbsent") return "Override · absent"
  if (decision == "holding") return "Holding present · absent evidence ${absentScore}"
  if (decision == "confirming") return "Confirming absent · score ${absentScore}"
  if (decision == "uncertain") return "Uncertain · retaining ${resolved} · scores ${presentScore}/${absentScore}"
  return "${decision.capitalize()} · scores ${presentScore}/${absentScore}"
}

private List configuredSourceRecords() {
  String raw = settings?.sources?.toString() ?: ""
  List lines = raw.readLines().collect { safeText(it, 140) }.findAll { it }
  if (!lines) throw new IllegalArgumentException("Enter at least one evidence source.")
  if (lines.size() > MAX_SOURCES) throw new IllegalArgumentException("Configure no more than ${MAX_SOURCES} sources.")
  Map unique = [:]
  lines.each { String line ->
    List parts = line.split(/\s*\|\s*/, -1).collect { it.trim() }
    if (parts.size() != 3 || !parts[0]) {
      throw new IllegalArgumentException("Each source must use Name | weight | expiry minutes.")
    }
    String name = safeText(parts[0], 60)
    int weight = parseBounded(parts[1], "weight for ${name}", 1, 10)
    int ttl = parseBounded(parts[2], "expiry for ${name}", 1, 1440)
    String key = sourceKey(name)
    if (unique.containsKey(key)) throw new IllegalArgumentException("Source names must be unique; '${name}' is duplicated.")
    unique[key] = [key:key, name:name, weight:weight, ttlMinutes:ttl]
  }
  return unique.values() as List
}

private void validateThresholds(List configured) {
  int total = configured.sum { it.weight } ?: 0
  int present = numberSetting("presentScoreRequired", 3, 1, 120)
  int absent = numberSetting("absentScoreRequired", 3, 1, 120)
  if (present > total) throw new IllegalArgumentException("Present threshold ${present} exceeds the configured weight total ${total}.")
  if (absent > total) throw new IllegalArgumentException("Absent threshold ${absent} exceeds the configured weight total ${total}.")
}

private Map resolveSource(String requested, List configured) {
  String text = requested?.trim()
  if (!text && configured.size() == 1) return configured[0]
  if (!text) throw new IllegalArgumentException("Provide a source name when more than one source is configured.")
  String key = sourceKey(text)
  Map result = configured.find { it.key == key }
  if (!result) throw new IllegalArgumentException("Unknown source '${safeText(text, 60)}'. Use a name from Evidence sources.")
  return result
}

private void retainConfiguredReports(List configured) {
  Map previous = state.reports instanceof Map ? state.reports : [:]
  Map retained = [:]
  configured.each { if (previous[it.key] instanceof Map) retained[it.key] = previous[it.key] }
  state.reports = retained
}

private void recordConfigurationError(String message) {
  unschedule()
  String detail = safeText(message ?: "The presence configuration is invalid.", 200)
  publishButtonCount()
  sendChanged("decisionState", "error")
  sendChanged("presence", state.resolvedState == "absent" ? "not present" : "present")
  sendChanged("secondsToNextChange", 0)
  sendChanged("conditionSummary", "Configuration error · ${detail}")
  sendChanged("lastError", detail)
  log.warn "Presence Consensus configuration error: ${detail}"
}

private void rejectCommand(String message) {
  String detail = safeText(message, 200)
  sendChanged("lastError", detail)
  log.warn "Presence Consensus rejected a command: ${detail}"
}

private int parseBounded(String raw, String label, int minimum, int maximum) {
  try {
    int value = raw.toBigDecimal().intValueExact()
    if (value < minimum || value > maximum) throw new IllegalArgumentException("${label.capitalize()} must be from ${minimum} to ${maximum}.")
    return value
  } catch (ArithmeticException | NumberFormatException ignored) {
    throw new IllegalArgumentException("${label.capitalize()} must be a whole number from ${minimum} to ${maximum}.")
  }
}

private int boundedMinutes(BigDecimal minutes) {
  try {
    return Math.max(1, Math.min(1440, minutes?.intValue() ?: 1))
  } catch (Exception ignored) {
    return 1
  }
}

private int numberSetting(String name, int fallback, int minimum, int maximum) {
  try {
    int value = settings?.get(name) != null ? settings.get(name).toString().toBigDecimal().intValue() : fallback
    return Math.max(minimum, Math.min(maximum, value))
  } catch (Exception ignored) {
    return fallback
  }
}

private String enumSetting(String name, String fallback, List allowed) {
  String value = settings?.get(name)?.toString()
  return value in allowed ? value : fallback
}

private String initialPresence() {
  return enumSetting("initialPresence", "present", ["present", "absent"])
}

private String sourceKey(String value) {
  return value.toLowerCase().replaceAll(/\s+/, " ").trim()
}

private String localText(long epoch) {
  TimeZone zone = location?.timeZone ?: TimeZone.getTimeZone("UTC")
  return new Date(epoch).format("yyyy-MM-dd HH:mm:ss z", zone)
}

private String safeText(Object value, int maximum) {
  String text = value == null ? "" : value.toString().replaceAll(/[\r\n\t]+/, " ").trim()
  return text.size() <= maximum ? text : text.take(Math.max(0, maximum - 1)) + "…"
}

private void emitNotification(int button, String type, String text) {
  String safe = safeText(text, 240)
  sendEvent(name:"notificationText", value:safe)
  sendEvent(name:"notificationType", value:type)
  sendEvent(name:"pushed", value:button, isStateChange:true, descriptionText:safe)
}

def testPresentEvent() { emitNotification(1, "test", "TEST: Presence Consensus present event.") }
def testAbsentEvent() { emitNotification(2, "test", "TEST: Presence Consensus absent event.") }
def testUncertainEvent() { emitNotification(3, "test", "TEST: Presence Consensus uncertain event.") }

def push(BigDecimal buttonNumber) {
  int button = buttonNumber?.intValue() ?: 0
  if (button == 1) testPresentEvent()
  else if (button == 2) testAbsentEvent()
  else if (button == 3) testUncertainEvent()
  else rejectCommand("Button number must be 1, 2 or 3.")
}

def refresh() { evaluateConsensus() }

def logsOff() {
  device.updateSetting("logEnable", [value:"false", type:"bool"])
}

private void publishButtonCount() {
  sendEvent(name:"numberOfButtons", value:NUMBER_OF_BUTTONS)
}

private void sendChanged(String name, Object value) {
  if (device.currentValue(name)?.toString() != value?.toString()) sendEvent(name:name, value:value)
}
