package me.wiefferink.interactivemessenger.processing;

import me.wiefferink.interactivemessenger.generators.ConsoleGenerator;
import me.wiefferink.interactivemessenger.generators.TellrawGenerator;
import me.wiefferink.interactivemessenger.parsers.YamlParser;
import me.wiefferink.interactivemessenger.source.MessageProvider;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Message {

	// CONFIGURATION
	private static boolean useInteractiveMessages = true;
	private static boolean useColorsInConsole = false;
	private static Logger logger = null;
	private static MessageProvider messageProvider = null;

	// Define the symbols used for variables
	public static final String VARIABLESTART = "%";
	public static final String VARIABLEEND = "%";
	public static final String LANGUAGEVARIABLE = "lang:";
	public static final Pattern variablePattern = Pattern.compile(Pattern.quote(VARIABLESTART)+"[a-zA-Z]+?"+Pattern.quote(VARIABLEEND));
	public static final Pattern variables = Pattern.compile(
			Pattern.quote(VARIABLESTART)+
					Pattern.quote(LANGUAGEVARIABLE)+"[a-zA-Z-]+?"+    // Language key
					"(\\|(.*?\\|)+?)?"+                                // Optional message arguments
					Pattern.quote(VARIABLEEND)
	);

	private static HashMap<Integer, Pattern> indexPatterns = new HashMap<>();

	/**
	 * Get a cached variable index tagPattern
	 * @param index The index of the variable tagPattern
	 * @return The tagPattern for the given index
	 */
	private static Pattern getIndexPattern(int index) {
		Pattern result = indexPatterns.get(index);
		if(result == null) {
			result = Pattern.compile(Pattern.quote(VARIABLESTART)+index+Pattern.quote(VARIABLEEND));
			indexPatterns.put(index, result);
		}
		return result;
	}
	// Language variable used to insert a prefix
	public static final String CHATLANGUAGEVARIABLE = "prefix";
	// Maximum number of replacement rounds (the replaced value can have variables again)
	public static final int REPLACEMENTLIMIT = 100;
	// Limit of the client is 32767 for the complete message
	public static final int MAXIMUMJSONLENGTH = 30000;
	// If sending a fancy message does not work we disable it for this run of the server
	private static boolean fancyWorks = true;

	// INSTANCE VARIABLES
	private List<String> message;
	private Object[] replacements;
	private String key = null;
	private boolean doLanguageReplacements = true;
	private boolean inline = false;

	/**
	 * Initialize the Message class
	 * @param provider The provider to use for getting messages based on keys
	 * @param theLogger The logger to use for logging warning and error messages
	 */
	public static void init(MessageProvider provider, Logger theLogger) {
		messageProvider = provider;
		logger = theLogger;
	}

	/**
	 * Internal use only
	 */
	private Message() {
		message = new ArrayList<>();
	}

	/**
	 * Empty message object
	 * @return this
	 */
	public static Message empty() {
		return new Message();
	}

	/**
	 * Construct a message from a language key
	 * Requires a MessageProvider to be set
	 * @param key The key of the message to use
	 * @return this
	 */
	public static Message fromKey(String key) {
		return new Message().setMessageFromKey(key);
	}

	/**
	 * Construct a message from a string
	 * @param message The message to use
	 * @return this
	 */
	public static Message fromString(String message) {
		return new Message().setMessage(message);
	}

	/**
	 * Construct a message from a string list
	 * @param message The message to use
	 * @return this
	 */
	public static Message fromList(List<String> message) {
		return new Message().setMessage(message);
	}

	/**
	 * Enable or disable the use of fancy messages
	 * @param enabled true to enable, false to disable
	 */
	public static void useFancyMessages(boolean enabled) {
		useInteractiveMessages = enabled;
	}

	/**
	 * Enable or disable the use of colors when sending a message to a target that is not a Player (console, log, etcetera)
	 * @param enabled true to enable, false to disable
	 */
	public static void useColorsInConsole(boolean enabled) {
		useColorsInConsole = enabled;
	}

	/**
	 * Get the message with all replacements done
	 * @return Message as a list
	 */
	public List<String> get() {
		doReplacements();
		return message;
	}

	/**
	 * Get the message with all replacements done
	 * @param limit the limit to hold to
	 * @return Message as a list
	 */
	private List<String> get(Limit limit) {
		if(limit.reached()) {
			return new ArrayList<>();
		}
		doReplacements(limit);
		return message;
	}

	/**
	 * Get the message with all replacements done
	 * @return Message as a string
	 */
	public String getSingle() {
		doReplacements();
		return StringUtils.join(message, "");
	}

	/**
	 * Get the raw message without replacing anything
	 * @return The message
	 */
	public List<String> getRaw() {
		return message;
	}

	/**
	 * Get raw message as string
	 * @return The raw message
	 */
	public String getSingleRaw() {
		return StringUtils.join(message, "");
	}

	/**
	 * Get a plain string for the message (for example for using in the console)
	 * @return The message as simple string
	 */
	public String getPlain() {
		doReplacements();
		return ConsoleGenerator.generate(YamlParser.parse(message));
	}

	/**
	 * Check if the message is empty
	 * @return true if the message is empty, otherwise false
	 */
	public boolean isEmpty() {
		for(String part : message) {
			if(!part.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Add the default prefix to the message
	 * @param doIt true if the prefix should be added, otherwise false
	 * @return this
	 */
	public Message prefix(boolean doIt) {
		if(doIt) {
			message.add(0, VARIABLESTART+LANGUAGEVARIABLE+CHATLANGUAGEVARIABLE+VARIABLEEND);
		}
		return this;
	}

	/**
	 * Add the default prefix to the message
	 * @return this
	 */
	public Message prefix() {
		return prefix(true);
	}

	/**
	 * Set the replacements to apply to the message
	 * @param replacements The replacements to apply
	 *					 - GeneralRegion: All region replacements are applied
	 *					 - Message: Message is inserted
	 *					 - other: index tag is replaced, like %0%
	 * @return this
	 */
	public Message replacements(Object... replacements) {
		this.replacements = replacements;
		return this;
	}

	/**
	 * Append lines to the message
	 * @param lines The lines to append
	 * @return this
	 */
	public Message append(List<String> lines) {
		message.addAll(lines);
		return this;
	}

	/**
	 * Append a message to this message
	 * @param message The message to append
	 * @return this
	 */
	public Message append(Message message) {
		return append(message.get());
	}

	/**
	 * Append lines to the message
	 * @param line The line to append
	 * @return this
	 */
	public Message append(String line) {
		message.add(line);
		return this;
	}

	/**
	 * Prepend lines to the message
	 * @param lines The lines to prepend
	 * @return this
	 */
	public Message prepend(List<String> lines) {
		message.addAll(0, lines);
		return this;
	}

	/**
	 * Prepend a message to this message
	 * @param message The message to prepend
	 * @return this
	 */
	public Message prepend(Message message) {
		return prepend(message.get());
	}

	/**
	 * Prepend lines to the message
	 * @param line The line to prepend
	 * @return this
	 */
	public Message prepend(String line) {
		message.add(0, line);
		return this;
	}

	/**
	 * Turn off language replacements for this message
	 * @return this
	 */
	public Message noLanguageReplacements() {
		doLanguageReplacements = false;
		return this;
	}

	/**
	 * Mark this message as inline, used for insertion into other messages
	 * @return this
	 */
	public Message inline() {
		inline = true;
		return this;
	}

	/**
	 * Send the message to a target
	 * @param target The target to send the message to (Player, CommandSender, Logger)
	 * @return this
	 */
	public Message send(Object target) {
		if(message == null || message.size() == 0 || (message.size() == 1 && message.get(0).length() == 0) || target == null) {
			return this;
		}
		doReplacements();
		if(target instanceof Player) {
			boolean sendPlain = true;
			if(useInteractiveMessages && fancyWorks) {
				try {
					boolean result = true;
					List<String> jsonMessages = TellrawGenerator.generate(YamlParser.parse(message));
					for(String jsonMessage : jsonMessages) {
						if(jsonMessage.length() > MAXIMUMJSONLENGTH) {
							error("Message with key", key, "could not be send, results in a JSON string that is too big to send to the client, start of the message:", getMessageStart(this, 200));
							return this;
						}
						result &= Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tellraw "+((Player)target).getName()+" "+jsonMessage);
					}
					sendPlain = !result;
					fancyWorks = result;
				} catch(Exception e) {
					fancyWorks = false;
					error("Sending fancy message did not work, falling back to plain messages. Message key:", key, ", error:", ExceptionUtils.getStackTrace(e));
				}
			}
			if(sendPlain) { // Fancy messages disabled or broken
				((Player)target).sendMessage(ConsoleGenerator.generate(YamlParser.parse(message)));
			}
		} else {
			String plainMessage = ConsoleGenerator.generate(YamlParser.parse(message));
			// Strip colors if disabled
			if(!useColorsInConsole) {
				plainMessage = ChatColor.stripColor(plainMessage);
			}

			// Send to the target
			if(target instanceof CommandSender) {
				((CommandSender)target).sendMessage(plainMessage);
			} else if(target instanceof Logger) {
				((Logger)target).info(ChatColor.stripColor(plainMessage));
			} else if(target instanceof BufferedWriter) {
				try {
					((BufferedWriter)target).write(ChatColor.stripColor(plainMessage));
					((BufferedWriter)target).newLine();
				} catch(IOException e) {
					warn("Exception while writing to BufferedWriter:", ExceptionUtils.getStackTrace(e));
				}
			} else {
				warn("Could not send message (key: "+key+") because the target ("+target.getClass().getName()+") is not recognized, message: "+plainMessage);
			}
		}
		return this;
	}


	// INTERNAL METHODS

	/**
	 * Set the internal message
	 * @param message The message to set
	 * @return this
	 */
	private Message setMessage(List<String> message) {
		this.message = message;
		if(this.message == null) {
			this.message = new ArrayList<>();
		}
		return this;
	}

	/**
	 * Set the internal message with a key
	 * @param key The message key to get the message for
	 * @return this
	 */
	private Message setMessageFromKey(String key) {
		this.key = key;
		if(messageProvider == null) {
			error("Tried to get message with key", key+", but there is no MessageProvider!");
		} else {
			this.setMessage(messageProvider.getMessage(key));
		}
		return this;
	}

	/**
	 * Set the internal message with a string
	 * @param message The message to set
	 * @return this
	 */
	private Message setMessage(String message) {
		List<String> list = new ArrayList<>();
		list.add(message);
		return this.setMessage(list);
	}

	/**
	 * Apply all replacements to the message
	 * @return this
	 */
	public Message doReplacements() {
		Limit limit = new Limit(REPLACEMENTLIMIT, this);
		doReplacements(limit);
		//depthPrint(limit, "Replacing took", System.currentTimeMillis()-limit.started, "milliseconds", this);
		return this;
	}

	private Message doReplacements(Limit limit) {
		if(limit.reached()) {
			return this;
		}

		limit.depth++;

		//depthPrint(limit, ">>> doReplacements:", message, limit);
		// Replace variables until they are all gone, or when the limit is reached
		try {
			List<String> outerOriginal;
			// Repeat replacements for if language replacements introduced new variables
			int fullRounds = 0;
			do {
				outerOriginal = new ArrayList<>(message);
				List<String> innerOriginal;
				limit.left--;
				if(limit.reached()) {
					break;
				}

				// Do argument replacements
				do {
					innerOriginal = new ArrayList<>(message);
					if(limit.reached()) {
						break;
					}
					replaceArgumentVariables(limit);
				} while(!message.equals(innerOriginal));

				// Do language replacements
				if(doLanguageReplacements) {
					do {
						innerOriginal = new ArrayList<>(message);
						if(limit.reached()) {
							break;
						}
						replaceLanguageVariables(limit);
					} while(!message.equals(innerOriginal));
				}

				fullRounds++;
			} while(!message.equals(outerOriginal));

			// Increase limit by one to compensate for the last round where no replacements have been done
			if(!limit.reached() && fullRounds >= 1) {
				limit.left++;
			}
		} catch(StackOverflowError e) {
			limit.left = 0;
			limit.notified = true;
			error("Too many recursive replacements for message with key: "+limit.message.key+" (probably includes itself as replacement), start of the message: "+getMessageStart(limit.message, 200));
		}
		limit.depth--;
		return this;
	}

	/**
	 * Replace argument variables in a message
	 * The arguments to apply as replacements:
	 * - If it is a GeneralRegion the replacements of the region will be applied
	 * - Else the parameter will replace its number surrounded with VARIABLESTART and VARIABLEEND
	 */
	private void replaceArgumentVariables(Limit limit) {
		limit.depth++;
		//depthPrint(limit, ">>> replaceArgumentVariables:", message, limit);
		if(message == null || message.size() == 0 || replacements == null || limit.reached()) {
			//depthPrint(limit, "quick return");
			limit.depth--;
			return;
		}

		for(int i = 0; i < message.size(); i++) {
			int number = 0;
			for(Object param : replacements) {
				String line = message.get(i);
				if(param != null) {
					if(param instanceof ReplacementProvider) {
						// Find the first non-escaped named variable
						Matcher matcher = variablePattern.matcher(line);
						int startAt = 0;
						while(matcher.find()) {
							// Check for escaping
							int beforeAt = matcher.start()-1;
							if(beforeAt >= 0 && line.charAt(beforeAt) == YamlParser.ESCAPE_CHAR) {
								//depthPrint(limit, "skipping named variable:", matcher.group(), limit);
								continue;
							}
							//depthPrint(limit, "replacing named variable:", matcher.group());

							// Insert replacement provided by the ReplacementProvider
							Object replacement = ((ReplacementProvider)param).provideReplacement(matcher.group().substring(1, matcher.group().length()-1));
							if(replacement != null) {
								String result = "";
								// Prefix
								if(matcher.start() > 0) {
									result += line.substring(0, matcher.start());
								}
								// Replacement
								String add = replacement.toString();
								result += add;
								// Suffix
								if(matcher.end() < line.length()) {
									result += line.substring(matcher.end());
								}

								message.set(i, result);
								line = result;
								int matcherStart = matcher.start();
								matcher = variablePattern.matcher(line);
								matcher.region(matcherStart+add.length(), line.length());
							}
						}
					} else {
						// Find first non-escaped numbered variable
						Matcher matcher = getIndexPattern(number).matcher(line);
						while(matcher.find()) {
							// Check for escaping
							int beforeAt = matcher.start()-1;
							if(beforeAt >= 0 && line.charAt(beforeAt) == YamlParser.ESCAPE_CHAR) {
								//depthPrint(limit, "skipping indexed variable:", matcher.group(), limit);
								continue;
							}
							//depthPrint(limit, "replacing indexed variable:", matcher.group());

							// Insert another Message
							if(param instanceof Message) {
								int startDiff = message.size()-i;
								//depthPrint(limit, "insert message raw:", ((Message)param).message);
								Message mParam = (Message)param;

								// Insert inline
								if(mParam.inline) {
									message.set(i, insert(line, mParam.getSingle(), matcher.start(), matcher.end()));
								}

								// Insert as message
								else {
									List<String> insertMessage = ((Message)param).get(limit);
									//depthPrint(limit, "insert message resolved:", ((Message)param).message);
									if(limit.reached()) {
										limit.depth--;
										return;
									}
									YamlParser.insertMessage(message, insertMessage, i, matcher.start(), matcher.end());
									// Skip to end of insert
									i = message.size()-startDiff;
								}
							}

							// Insert a simple string
							else {
								// Insert it inline, assuming this might be user input, therefore escaping it
								//depthPrint(limit, "insert string:", param.toString());
								message.set(i, insert(line, YamlParser.escape(param.toString()), matcher.start(), matcher.end()));
							}
							break; // Maximum of one replacement
						}
						number++;
					}
				}
			}
		}
		limit.depth--;
	}

	/**
	 * Insert a string into another one, replacing a part of the base
	 * @param base   The base string to insert into
	 * @param insert The string to insert
	 * @param start  The start of the region to replace
	 * @param end	The end of the region to replace
	 * @return The formatted string
	 */
	private String insert(String base, String insert, int start, int end) {
		String newMessage = "";
		if(start > 0) {
			newMessage += base.substring(0, start);
		}
		newMessage += insert;
		if(end < base.length()) {
			newMessage += base.substring(end);
		}
		return newMessage;
	}

	/**
	 * Replace all language variables in a message
	 */
	private void replaceLanguageVariables(Limit limit) {
		limit.depth++;
		//depthPrint(limit, ">>> replaceLanguageVariables:", message, limit);
		if(message == null || message.size() == 0 || limit.reached()) {
			//depthPrint(limit, "quick return");
			limit.depth--;
			return;
		}

		for(int i = 0; i < message.size(); i++) {
			Matcher matcher = variables.matcher(message.get(i));
			while(matcher.find()) {
				// Check for escaping
				int beforeAt = matcher.start()-1;
				if(beforeAt >= 0 && message.get(i).charAt(beforeAt) == YamlParser.ESCAPE_CHAR) {
					//depthPrint(limit, "skipping variable:", matcher.group(), limit);
					continue;
				}
				//depthPrint(limit, "replacing variable:", matcher.group());

				// Parse arguments
				String variable = matcher.group();
				String key;
				Object[] arguments = null;
				if(variable.contains("|")) {
					key = variable.substring(VARIABLESTART.length()+LANGUAGEVARIABLE.length(), variable.indexOf("|"));
					String[] stringArguments = variable.substring(variable.indexOf("|")+1, variable.length()-VARIABLEEND.length()).split("\\|");
					// Wrap arguments in Message object to prevent escaping
					arguments = new Message[stringArguments.length];
					for(int argumentIndex = 0; argumentIndex < stringArguments.length; argumentIndex++) {
						// Marks as inline to prevent spreading the language variable onto multiple lines
						arguments[argumentIndex] = Message.fromString(stringArguments[argumentIndex]).inline();
					}
				} else {
					key = variable.substring(VARIABLESTART.length()+LANGUAGEVARIABLE.length(), variable.length()-VARIABLEEND.length());
				}
				Message insert = Message.fromKey(key);
				if(arguments != null) {
					insert.replacements(arguments);
				}

				// Insert message
				int startDiff = message.size()-i;
				List<String> insertMessage = insert.get(limit);
				if(limit.reached()) {
					limit.depth--;
					return;
				}
				YamlParser.insertMessage(message, insertMessage, i, matcher.start(), matcher.end());
				// Skip to end of insert
				i = message.size()-startDiff;
				break; // Maximum of one replacement
			}
		}
		limit.depth--;
	}

	@Override
	public String toString() {
		return "Message(key:"+key+", message:"+message+")";
	}


	/**
	 * Class to store a limit
	 */
	private class Limit {
		public int left;
		public int depth;
		public boolean notified = false;
		public Message message;
		public long started;

		/**
		 * Set the initial limit
		 * @param count The limit to use
		 * @param message The message this limit is started for
		 */
		public Limit(int count, Message message) {
			this.left = count;
			this.depth = 0;
			this.message = message;
			this.started = System.currentTimeMillis();
		}

		/**
		 * Check if the limit is reached
		 * @return true if the limit is reached, otherwise false
		 */
		public boolean reached() {
			boolean reached = left <= 0;
			if(reached && !notified) {
				notified = true;
				error("Reached replacement limit, probably has replacements loops, problematic message key: "+message.key+", first characters of the message: "+getMessageStart(message, 200));
			}
			return reached;
		}

		@Override
		public String toString() {
			return "Limit(left: "+left+", notified: "+notified+", depth: "+depth+", message.key: "+message.key+")";
		}
	}


	/**
	 * Get a start of the message with a maximum length
	 * @param message	   The message
	 * @param maximumLength The maximum length to return
	 * @return The start of the message with at most maximumLength characters
	 */
	private static String getMessageStart(Message message, int maximumLength) {
		String messageStart = "";
		for(int i = 0; i < message.getRaw().size() && messageStart.length() < maximumLength; i++) {
			messageStart += message.getRaw().get(i).substring(0, Math.min(maximumLength, message.getRaw().get(i).length()));
		}
		return messageStart.substring(0, Math.min(maximumLength, messageStart.length()));
	}

	/**
	 * Debug method to print indented messages
	 * @param limit   The limit to use for the depth
	 * @param message The message to print indented
	 */
	private static void depthPrint(Limit limit, Object... message) {
		String indent = "";
		for(int i = 0; i < limit.depth; i++) {
			indent += "  ";
		}
		Object[] iMessage = new Object[message.length+1];
		iMessage[0] = indent;
		System.arraycopy(message, 0, iMessage, 1, message.length);
		info(iMessage);
	}

	/**
	 * Print information to the console
	 * @param message The message to print
	 */
	public static void info(Object... message) {
		if(logger != null) {
			logger.info(StringUtils.join(message, " "));
		}
	}

	/**
	 * Print a warning to the console
	 * @param message The message to print
	 */
	public static void warn(Object... message) {
		if(logger != null) {
			logger.warning(StringUtils.join(message, " "));
		}
	}

	/**
	 * Print an error to the console
	 * @param message The messagfe to print
	 */
	public static void error(Object... message) {
		if(logger != null) {
			logger.severe(StringUtils.join(message, " "));
		}
	}
}
