package uk.me.rkd.jsipp.runtime;

import gov.nist.javax.sip.message.SIPMessage;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import uk.me.rkd.jsipp.Configuration;
import uk.me.rkd.jsipp.compiler.SimpleVariableTable;
import uk.me.rkd.jsipp.compiler.phases.CallPhase;
import uk.me.rkd.jsipp.compiler.phases.Pause;
import uk.me.rkd.jsipp.compiler.phases.RecvPhase;
import uk.me.rkd.jsipp.compiler.phases.SendPhase;
import uk.me.rkd.jsipp.runtime.Statistics.StatType;
import uk.me.rkd.jsipp.runtime.network.RTPSocketManager;
import uk.me.rkd.jsipp.runtime.network.SocketManager;
import uk.me.rkd.jsipp.runtime.parsers.SIPpMessageParser;
import uk.me.rkd.jsipp.runtime.parsers.SipUtils;

public class Call implements TimerTask {

	private final int callNumber;
	private final String callId;

	/**
	 * @return the callId
	 */
	public String getCallId() {
		return callId;
	}

	final int NO_TIMEOUT = -1;
	private int phaseIndex = 0;
	private List<CallPhase> phases;
	private SocketManager sm;
	private long timeoutEnds = NO_TIMEOUT;
	private Timer timer;
	private SIPMessage lastMessage;
	private CallVariables variables;
	private boolean alreadyFinished = false;
	private Timeout currentTimeout;
	private String scenarioName;
	private int mediaPort = 0;

	public class CallVariables extends SimpleVariableTable {

		@Override
		public String get(String name) {
			try {
				if (name.equals("local_port")) {
					return Integer.toString(getLocalAddress().getPort());
				} else if (name.equals("remote_port")) {
					return Integer.toString(getRemoteAddress().getPort());
				} else if (name.equals("local_ip")) {
					return getLocalAddress().getAddress().getHostAddress();
				} else if (name.equals("remote_ip")) {
					return getRemoteAddress().getAddress().getHostAddress();
				} else if (name.equals("local_ip_type")) {
					return (getLocalAddress().getAddress() instanceof Inet6Address) ? "6" : "4";
				} else if (name.equals("media_ip")) {
					return "127.0.0.1";
				} else if (name.equals("media_port")) {
					return Integer.toString(mediaPort);
				} else if (name.equals("media_ip_type")) {
					return "4";
				}
			} catch (IOException e) {
				e.printStackTrace();
				return null;
			}

			if (vars.containsKey(name)) {
				return vars.get(name);
			}

			String global = SimpleVariableTable.global().get(name);

			if (global != null) {
				return global;
			}

			if (name.startsWith("last_") && lastMessage != null) {
				String headerName = name.replace("last_", "");
				return lastMessage.getHeaderAsFormattedString(headerName).trim();
			}
			return null;
		}

	}

	private void publishStat(StatType type, boolean include_idx) {
		publishStat(type, include_idx, false);
	}
	
	private void publishStat(StatType type, boolean include_idx, boolean receiving) {
		String timestamp = Double.toString(System.currentTimeMillis() % 1000.0);
		if (include_idx && receiving) {
			String identifier = SipUtils.methodOrStatusCode(this.lastMessage.getFirstLine());
			Statistics.INSTANCE.report(type, timestamp, this.scenarioName, Integer.toString(this.callNumber), this.callId, Integer.toString(this.phaseIndex), identifier);
		} else if (include_idx) {
			Statistics.INSTANCE.report(type, timestamp, this.scenarioName, Integer.toString(this.callNumber), this.callId, Integer.toString(this.phaseIndex));
		} else {
			Statistics.INSTANCE.report(type, timestamp, this.scenarioName, Integer.toString(this.callNumber), this.callId);
		}
	}

	public void registerSocket() throws IOException {
		this.sm.add(this);
	}

	public Call(int callNum, String callId, String scenarioname, List<CallPhase> phases, SocketManager sm, Timer t) {
		this.variables = new CallVariables();
		this.callNumber = callNum;
		this.callId = callId;
		this.scenarioName = scenarioname;
		this.variables.putKeyword("call_number", Integer.toString(callNum));
		this.variables.putKeyword("call_id", this.callId);
		this.phases = phases;
		this.sm = sm;
		this.timer = t;
		if (Configuration.INSTANCE.isRtpSink()) {
		    try {
		        this.mediaPort = RTPSocketManager.INSTANCE.add(callId);
		    } catch (IOException e) {
		        // TODO Auto-generated catch block
		        e.printStackTrace();
		    }
		}
		publishStat(StatType.CALL_BEGIN, false);
	}

	private void success() {
		if (!alreadyFinished) {
			publishStat(StatType.CALL_SUCCESS, false);
			end();
		}
	}

	private void fail() {
		publishStat(StatType.CALL_FAILURE, false);
		end();
	}

	private void end() {
	    if (Configuration.INSTANCE.isRtpSink()) {
	        RTPSocketManager.INSTANCE.remove(this.callId);
	    }
		alreadyFinished = true;
		try {
			this.sm.remove(this);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public boolean hasCompleted() {
		return this.phaseIndex >= this.phases.size();
	}

	public synchronized void reschedule(long when) {
		if (this.currentTimeout != null) {
			this.currentTimeout.cancel();
		}
		//System.out.println("Rescheduling for " + Long.toString(when) + " ms");
		this.currentTimeout = this.timer.newTimeout(this, when, TimeUnit.MILLISECONDS);
	}

	public synchronized void run(Timeout timeout) {
		//System.out.println(String.format("Call %d, phase %d", this.getNumber(), this.phaseIndex));
		if (hasCompleted()) {
			this.success();
		} else {
			CallPhase currentPhase = getCurrentPhase();
			this.timer = timeout.timer();

			// If we're waiting to receive, check for timeout
			if (currentPhase instanceof RecvPhase) {
				if (this.timeoutEnds == NO_TIMEOUT) {
					this.timeoutEnds = ((RecvPhase) currentPhase).timeout + System.currentTimeMillis();
				}
				long untilTimeout = this.timeoutEnds - System.currentTimeMillis();
				if (untilTimeout < 0) {
					publishStat(StatType.RECV_TIMED_OUT, true);
					this.fail();
				} else {
					// We haven't timed out yet - reschedule ourselves to run when we will time out
					reschedule(untilTimeout);
				}
			} else if (currentPhase instanceof Pause) {
				if (this.timeoutEnds == NO_TIMEOUT) {
					this.timeoutEnds = ((Pause) currentPhase).getDuration() + System.currentTimeMillis();
				}
				long untilTimeout = this.timeoutEnds - System.currentTimeMillis();
				if (untilTimeout < 0) {
					publishStat(StatType.PHASE_SUCCESS, true);
					nextPhase();
					this.run(timeout);
				} else {
					// We haven't timed out yet - reschedule ourselves to run when we will time out
					reschedule(untilTimeout);
				}
			} else if (currentPhase instanceof SendPhase) {
				// We're sending - just send and move on
				send();
				nextPhase();
				this.run(timeout);
			}
		}
	}

	private void send() {
		SendPhase currentPhase = (SendPhase) getCurrentPhase();
		this.variables.putKeyword("branch", "z9hG4bK" + UUID.randomUUID().toString());
		try {
			// Do a first pass of keyword replacement, so we can calculate the body length
			String message = KeywordReplacer.replaceKeywords(currentPhase.message, this.variables, false);
			int len = SIPpMessageParser.getBodyLength(message);
			this.variables.putKeyword("len", Integer.toString(len));

			// Do a second pass now that we know the value of [len]
			assert (!this.hasCompleted());
			message = KeywordReplacer.replaceKeywords(currentPhase.message, this.variables, false);
			this.sm.send(this.callNumber, message);
			publishStat(StatType.PHASE_SUCCESS, true);
		} catch (Exception e) {
			System.out.println("Send failed");
			e.printStackTrace();
			this.fail();
		}
	}

	public int getNumber() {
		return callNumber;
	}

	public synchronized void process_incoming(SIPMessage message) {
		this.timeoutEnds = NO_TIMEOUT;
		this.lastMessage = message;

		CallPhase phase = getCurrentPhase();
		if (phase.expected(message)) {
			publishStat(StatType.PHASE_SUCCESS, true, true);
			nextPhase();
			reschedule(0);
		} else {
			// No match - check if this was optional
			if (phase.isOptional()) {
				nextPhase();
				process_incoming(message);
				return;
			}
			publishStat(StatType.UNEXPECTED_MSG_RECVD, true, true);
			System.out.println("Expected " + phase.expected);
			this.fail();
		}
	}

	private CallPhase getCurrentPhase() {
		return this.phases.get(this.phaseIndex);
	}

	private void nextPhase() {
		this.timeoutEnds = NO_TIMEOUT;
		this.phaseIndex += 1;
	}

	InetSocketAddress getRemoteAddress() throws IOException {
		return (InetSocketAddress) this.sm.getdest(callNumber);
	}

	InetSocketAddress getLocalAddress() throws IOException {
		return (InetSocketAddress) this.sm.getaddr(callNumber);
	}
}
