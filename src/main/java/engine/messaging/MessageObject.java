package engine.messaging;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;

/**
 * A message. {@code queue == null} means broadcast; {@code receiverId != null} narrows delivery to receivers whose
 * {@link IMessageReceiver#receiverId()} matches.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(fluent = false)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MessageObject {
  Object sender;
  Long receiverId;
  MessageQueue queue;
  Object payload;
}
