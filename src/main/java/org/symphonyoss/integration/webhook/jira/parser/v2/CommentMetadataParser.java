/**
 * Copyright 2016-2017 Symphony Integrations - Symphony LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.symphonyoss.integration.webhook.jira.parser.v2;

import static org.symphonyoss.integration.webhook.jira.JiraEventConstants.ISSUE_EVENT_TYPE_NAME;
import static org.symphonyoss.integration.webhook.jira.JiraEventConstants.JIRA_ISSUE_COMMENTED;
import static org.symphonyoss.integration.webhook.jira.JiraEventConstants
    .JIRA_ISSUE_COMMENT_DELETED;
import static org.symphonyoss.integration.webhook.jira.JiraEventConstants.JIRA_ISSUE_COMMENT_EDITED;
import static org.symphonyoss.integration.webhook.jira.JiraParserConstants.ACTION_ENTITY_FIELD;
import static org.symphonyoss.integration.webhook.jira.JiraParserConstants.BODY_PATH;
import static org.symphonyoss.integration.webhook.jira.JiraParserConstants.COMMENT_PATH;
import static org.symphonyoss.integration.webhook.jira.JiraParserConstants.ID_PATH;
import static org.symphonyoss.integration.webhook.jira.JiraParserConstants.ISSUE_PATH;
import static org.symphonyoss.integration.webhook.jira.JiraParserConstants.LINK_ENTITY_FIELD;
import static org.symphonyoss.integration.webhook.jira.JiraParserConstants.VISIBILITY_PATH;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.symphonyoss.integration.entity.model.User;
import org.symphonyoss.integration.model.yaml.IntegrationProperties;
import org.symphonyoss.integration.parser.ParserUtils;
import org.symphonyoss.integration.parser.SafeString;
import org.symphonyoss.integration.service.UserService;
import org.symphonyoss.integration.webhook.jira.parser.v1.JiraParserUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class is responsible to validate the event 'jira:issue_updated' and event types
 * 'issue_commented', 'issue_comment_edited' and 'issue_comment_deleted' sent by JIRA Webhook when
 * the Agent version is equal to or greater than '1.46.0'.
 *
 * Created by aurojr on 25/04/17.
 */
@Component
public class CommentMetadataParser extends JiraMetadataParser {

  private static final Pattern userCommentPattern = Pattern.compile("(\\[~)([\\w.]+)(])");
  private static final String METADATA_FILE = "metadataIssueCommented.xml";
  private static final String TEMPLATE_FILE = "templateIssueCommented.xml";
  private static final String COMMENT_LINK_SUFFIX =
      "focusedCommentId=%s&amp;page=com.atlassian.jira.plugin.system"
          + ".issuetabpanels%%3Acomment-tabpanel#comment-%s";

  private final Map<String, String> actions = new HashMap<>();


  @Autowired
  public CommentMetadataParser(UserService userService,
      IntegrationProperties integrationProperties) {
    super(userService, integrationProperties);

    actions.put(JIRA_ISSUE_COMMENTED, "Commented");
    actions.put(JIRA_ISSUE_COMMENT_EDITED, "Edited Comment");
    actions.put(JIRA_ISSUE_COMMENT_DELETED, "Deleted Comment");
  }

  @Override
  protected String getTemplateFile() {
    return TEMPLATE_FILE;
  }

  @Override
  protected String getMetadataFile() {
    return METADATA_FILE;
  }

  @Override
  public List<String> getEvents() {
    return Arrays.asList(JIRA_ISSUE_COMMENTED, JIRA_ISSUE_COMMENT_DELETED,
        JIRA_ISSUE_COMMENT_EDITED);
  }

  @Override
  protected void preProcessInputData(JsonNode input) {
    super.preProcessInputData(input);
    processCommentLink(input);
    processCommentAction(input);
    processCommentMentions(input);
  }

  /**
   * This method adds an action field to the metadata json with a text indicating the performed
   * comment action (add, edit, delete)
   * @param input The root json node
   */
  private void processCommentAction(JsonNode input) {
    String webHookEvent = input.path(ISSUE_EVENT_TYPE_NAME).asText();
    ObjectNode commentNode = (ObjectNode) input.with(COMMENT_PATH);
    if (commentNode != null) {
      commentNode.put(ACTION_ENTITY_FIELD, actions.get(webHookEvent));
    }
  }

  /**
   * This method changes the self link sent by Jira into a common comment Jira url
   * @param input The root json node
   */
  private void processCommentLink(JsonNode input) {
    ObjectNode commentNode = getCommentNode(input);

    if (commentNode != null) {
      JsonNode issueNode = input.path(ISSUE_PATH);
      String linkedIssueField = getLinkedIssueField(issueNode);
      String linkedCommentLink = getLinkedCommentLink(linkedIssueField, commentNode);
      commentNode.put(LINK_ENTITY_FIELD, linkedCommentLink);
    }
  }

  /**
   * This method builds the comment permalink according to the issue link and the comment node,
   * i.e., the given issue link must be built based on the issue related to the given comment node
   * @param issueLink
   * @param commentNode
   * @return
   */
  private String getLinkedCommentLink(String issueLink, JsonNode commentNode) {
    String commentId = commentNode.path(ID_PATH).asText();
    StringBuilder commentLink = new StringBuilder(issueLink);

    if (!StringUtils.isEmpty(commentId)) {
      commentLink.append("?");
      commentLink.append(String.format(COMMENT_LINK_SUFFIX, commentId, commentId));
    }

    return commentLink.toString();
  }

  /**
   * This searches through the comment body and replaces
   * @param input
   */
  private void processCommentMentions(JsonNode input) {
    ObjectNode commentNode = getCommentNode(input);

    if (commentNode != null) {
      String comment = StringUtils.EMPTY;

      if (!isCommentRestricted(input)) {
        comment = formatComment(commentNode.path(BODY_PATH).asText()).toString();
      }
      commentNode.put(BODY_PATH, comment);
    }
  }

  private ObjectNode getCommentNode(JsonNode input) {
    return input.hasNonNull(COMMENT_PATH) ? (ObjectNode) input.path(COMMENT_PATH) : null;
  }

  /**
   * Jira's RTE syntax are not supported yet. For that reason, all jira formatting is removed in
   * this method, and the user mentions are replaced by Nexus soft mention ([~user])
   * @param comment The raw comment from Jira's webhook
   * @return Comment supported by MessageML v2 syntax
   */
  private SafeString formatComment(String comment) {

    if (StringUtils.isEmpty(comment)) {
      return SafeString.EMPTY_SAFE_STRING;
    }

    comment = JiraParserUtils.stripJiraFormatting(comment);

    SafeString safeComment = ParserUtils.escapeAndAddLineBreaks(comment);

    // FIXME: Uncomment me when soft mentions are supported on MessageML v2
//    Map<String, User> usersToMention = determineUserMentions(comment);
//    if (usersToMention != null && !usersToMention.isEmpty()) {
//      for (Map.Entry<String, User> userToMention : usersToMention.entrySet()) {
//        User user = userToMention.getValue();
//
//        safeComment.safeReplace(new SafeString(userToMention.getKey()),
//            ParserUtils.presentationFormat(MENTION_MARKUP, user.getUsername()));
//      }
//    }
    return safeComment;
  }

  private Map<String, User> determineUserMentions(String comment) {
    Set<String> userMentions = new HashSet<>();
    Map<String, User> usersToMention = new HashMap<>();
    Matcher matcher = userCommentPattern.matcher(comment);
    while (matcher.find()) {
      userMentions.add(matcher.group(2));
    }
    for (String userName : userMentions) {
      User user = getUserByUserName(userName);
      if (user != null && StringUtils.isNotEmpty(user.getEmailAddress())) {
        usersToMention.put(userName, user);
      }
    }
    return usersToMention;
  }

  /**
   * JIRA comments may be restricted to certain user groups on JIRA. This is indicated by the
   * presence of a "visibility" attribute on the comment. Thus, this method will deem a comment as
   * restricted if the "visibility" attribute is present, regardless of its content, as it is not
   * possible to evaluate the visibility restriction on JIRA against the rooms the webhook will post
   * to.
   * @param node JIRA root node.
   * @return Indication on whether the comment is restricted or not.
   */
  private boolean isCommentRestricted(JsonNode node) {
    return node.path(COMMENT_PATH).has(VISIBILITY_PATH);
  }
}