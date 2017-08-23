package eu.siacs.conversations.xmpp.rtt;

import android.os.Handler;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;

import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicBoolean;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.ui.ConversationActivity;
import eu.siacs.conversations.ui.ConversationFragment;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.rtt.RttEvent.Type;
import eu.siacs.conversations.xmpp.stanzas.MessagePacket;

public class RttEventListener implements TextWatcher {

	private Conversation conversation;
	private ConversationActivity conversationActivity;
	private ConversationFragment conversationFragment;

	/*
	 * addLast()	 -> add Element to the queue
	 * removeFirst() -> remove Element from the queue
	 * size()		 -> size of the queue
	 *
	 * It is synchronized because another method will be called every 700-1000 milliseconds which
	 * will empty the queue and actually send these events to the other device packed in <rtt />
	 * stanzas.
	 */
	private final LinkedList<RttEvent> rttEventQueue;

	/*
	 * Stores number of milliseconds from epoch at the time of the latest edit
	 */
	private long lastMessageMillis;

	/*
	 * Stores number of milliseconds from epoch at the time of the current edit
	 */
	private long currentMessageMillis;

	private static final long MIN_WAIT_DUR = 100;
	private static final int PACKET_SEND_DUR = 700;
	private static final int TYPING_TIMEOUT = Config.TYPING_TIMEOUT * 1000;
	private int currentIdleTime;
	private int rttStanzaSequence;

	private String _before, _after;

	private int length_before;

	private Handler h;
	private AtomicBoolean isTyping;

	public RttEventListener(ConversationFragment fragment, Conversation conversation, ConversationActivity activity) {
		this.conversationFragment = fragment;
		this.conversation = conversation;
		this.conversationActivity = activity;
		rttEventQueue = new LinkedList<>();
		currentMessageMillis = lastMessageMillis = 0;
		rttStanzaSequence = 0;
		isTyping = new AtomicBoolean(false);
		currentIdleTime = 0;
	}

	@Override
	public void beforeTextChanged(CharSequence s, int start, int count, int after) {
		if (!conversationFragment.ignoreOneEvent.get()) {
			_before = String.valueOf(s.subSequence(start, start + count));
			length_before = s.length();
		}
	}

	@Override
	public void onTextChanged(CharSequence s, int start, int before, int count) {
		if (!conversationFragment.ignoreOneEvent.get()) {
			int position;

			_after = String.valueOf(s.subSequence(start, start + count));

			currentMessageMillis = SystemClock.elapsedRealtime();
			if (lastMessageMillis == 0) {
				lastMessageMillis = currentMessageMillis;
			}

			if (_after.length() > _before.length()) {
				if (_after.startsWith(_before)) {
					String changedPart = String.valueOf(_after.subSequence(_before.length(), _after.length()));
					position = start + _before.length();
					addTextEvent(changedPart, position == length_before ? null : position);
				} else if (_after.endsWith(_before)) {
					String changedPart = String.valueOf(_after.subSequence(0, _after.length() - _before.length()));
					position = start;
					addTextEvent(changedPart, position == length_before ? null : position);
				} else {
					position = start + before;
					addEraseEvent(before, position == length_before ? null : position);
					addTextEvent(_after, start);
				}
			} else if (_after.length() == _before.length()) {
				position = start + before;
				addEraseEvent(before, position == length_before ? null : position);
				addTextEvent(_after, start);
			} else {
				if (_before.startsWith(_after)) {
					position = start + _before.length();
					addEraseEvent(_before.length() - _after.length(), position == length_before ? null : position);
				} else if (_before.endsWith(_after)) {
					position = start + _before.length() - _after.length();
					addEraseEvent(_before.length() - _after.length(), position == length_before ? null : position);
				} else {
					position = start + before;
					addEraseEvent(before, position == length_before ? null : position);
					addTextEvent(_after, start);
				}
			}
		}
	}

	@Override
	public void afterTextChanged(Editable s) {
		conversationFragment.ignoreOneEvent.compareAndSet(true, false);
		startSending();
	}

	private void addTextEvent(CharSequence text, Integer position) {
		if (text.length() > 0) {
			addWaitEvent();
			TextEvent t = new TextEvent();
			t.setPosition(position);
			t.setText(String.valueOf(text));

			synchronized (rttEventQueue) {
				if (rttEventQueue.size() > 0 && rttEventQueue.getLast().type == Type.TEXT) {
					TextEvent top = (TextEvent) rttEventQueue.getLast();
					if (top.getPosition() == null && t.getPosition() == null) {
						t.setText(top.getText() + t.getText());
						rttEventQueue.removeLast();
						Log.i(Config.LOGTAG, "Text RTT events merged");
					} else if (top.getPosition() != null && t.getPosition() != null && top.getPosition() + top.getText().length() == t.getPosition()) {
						t.setPosition(top.getPosition());
						t.setText(top.getText() + t.getText());
						rttEventQueue.removeLast();
						Log.i(Config.LOGTAG, "Text RTT events merged");
					}
				}

				rttEventQueue.addLast(t);
				Log.i(Config.LOGTAG, "Text RTT event with text = " + t.getText() + " at position = " + (t.getPosition() == null ? "end" : t.getPosition()));
			}
		}
	}

	private void addWaitEvent() {
		if (currentMessageMillis - lastMessageMillis > MIN_WAIT_DUR) {
			WaitEvent w = new WaitEvent();
			w.setWaitInterval(currentMessageMillis - lastMessageMillis);

			synchronized (rttEventQueue) {
				rttEventQueue.addLast(w);
				Log.i(Config.LOGTAG, "Wait RTT event with " + w.getWaitInterval() + " milliseconds");
			}
		}

		lastMessageMillis = currentMessageMillis;
	}

	private void addEraseEvent(Integer number, Integer position) {
		if (number > 0) {
			addWaitEvent();
			EraseEvent e = new EraseEvent();
			e.setPosition(position);
			e.setNumber(number);

			synchronized (rttEventQueue) {
				if (rttEventQueue.size() > 0 && rttEventQueue.getLast().type == Type.ERASE) {
					EraseEvent top = (EraseEvent) rttEventQueue.getLast();
					if (top.getPosition() == null && e.getPosition() == null) {
						e.setNumber(top.getNumber() + e.getNumber());
						rttEventQueue.removeLast();
						Log.i(Config.LOGTAG, "Erase RTT events merged");
					} else if (top.getPosition() != null && e.getPosition() != null && top.getPosition() - top.getNumber() == e.getPosition()) {
						e.setPosition(top.getPosition());
						e.setNumber(top.getNumber() + e.getNumber());
						rttEventQueue.removeLast();
						Log.i(Config.LOGTAG, "Erase RTT events merged");
					}
				}

				rttEventQueue.addLast(e);
				Log.i(Config.LOGTAG, "Erase RTT event to erase " + e.getNumber() + "characters from " + (e.getPosition() == null ? "end" : e.getPosition() + "th character"));
			}
		}
	}

	private void flushQueue() {
		synchronized (rttEventQueue) {
			boolean firstElement = true;
			if (rttEventQueue.size() > 0) {
				rttStanzaSequence++;
				currentIdleTime = 0;
				Element rtt = new Element("rtt", Namespace.RTT);
				rtt.setAttribute("seq", rttStanzaSequence);
				while (rttEventQueue.size() > 0) {
					RttEvent event = rttEventQueue.removeFirst();
					if (firstElement && event.getType() == Type.WAIT) {
						firstElement = false;
					} else if (rttEventQueue.size() == 0 && event.getType() == Type.WAIT) {
						continue;
					} else {
						rtt.addChild(event.toElement());
					}
				}
				MessagePacket messagePacket = new MessagePacket();
				messagePacket.addChild(rtt);
				conversationActivity.xmppConnectionService.sendRttEvent(conversation, messagePacket);
			} else {
				currentIdleTime += PACKET_SEND_DUR;
			}
		}
	}

	private void startSending() {
		if (isTyping.compareAndSet(false, true)) {
			h = new Handler();
			h.postDelayed(new Runnable() {
				@Override
				public void run() {
					flushQueue();
					if (currentIdleTime > TYPING_TIMEOUT) {
						isTyping.set(false);
					} else {
						h.postDelayed(this, PACKET_SEND_DUR);
					}
				}
			}, PACKET_SEND_DUR);
		}
	}

	public void stop() {
		flushQueue();
		isTyping.set(false);
		if (h != null) {
			h.removeCallbacksAndMessages(null);
		}
	}

	private void sendCancelStanza() {
		Element rttCancel = new Element("rtt", Namespace.RTT);
		rttCancel.setAttribute("event", "cancel");
		MessagePacket messagePacket = new MessagePacket();
		messagePacket.addChild(rttCancel);
		conversationActivity.xmppConnectionService.sendRttEvent(conversation, messagePacket);
	}

	private void sendResetStanza() {
		Element rttReset = new Element("rtt", Namespace.RTT);
		rttReset.setAttribute("event", "reset");
		rttStanzaSequence = 1;
		rttReset.setAttribute("seq", rttStanzaSequence);
		TextEvent resetEvent = new TextEvent();
		resetEvent.setText(conversationFragment.mEditMessage.getText().toString());
		rttReset.addChild(resetEvent.toElement());
		MessagePacket messagePacket = new MessagePacket();
		messagePacket.addChild(rttReset);
		conversationActivity.xmppConnectionService.sendRttEvent(conversation, messagePacket);
	}
}
