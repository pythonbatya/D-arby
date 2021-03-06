package com.example.darby.resource;

import com.example.darby.dao.H2Dao;
import com.example.darby.entity.EstimationScale;
import com.example.darby.entity.GameRoom;
import com.example.darby.entity.HistoryLog;
import com.example.darby.entity.Task;
import com.example.darby.entity.TaskEstimation;
import com.example.darby.service.JiraHelper;
import com.example.darby.service.SlackHelper;
import com.slack.api.app_backend.interactive_components.payload.BlockActionPayload;
import com.slack.api.bolt.context.builtin.ActionContext;
import com.slack.api.bolt.request.builtin.BlockActionRequest;
import com.slack.api.bolt.response.Response;
import com.slack.api.methods.SlackApiException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class PollHandler {
  public static final String NEXT_TASK_EVENT = "next_task_event";
  public static final String POLL_OPTION_SELECTED_EVENT = "poll_option_selected";
  public static final String POLL_ENDED_EVENT = "poll_ended_event";
  public static final Pattern POLL_OPTION_SELECTED_PATTERN = Pattern.compile("^" + POLL_OPTION_SELECTED_EVENT + "-.*$");
  public static final String TASK_ESTIMATED_EVENT = "task_estimated_event";
  public static final String CREATE_JIRA_ISSUE_EVENT = "create_jira_issue_event";

  private final H2Dao dao;
  private final SlackHelper slackHelper;
  private final JiraHelper jiraHelper;

  public PollHandler(H2Dao dao, SlackHelper slackHelper, JiraHelper jiraHelper) {
    this.dao = dao;
    this.slackHelper = slackHelper;
    this.jiraHelper = jiraHelper;
  }

  public Response handleStartTaskPoll(BlockActionRequest req, ActionContext ctx) throws IOException, SlackApiException {
    String channelId = req.getPayload().getContainer().getChannelId();
    String gameRoomId = req.getPayload().getActions().get(0).getValue();
    String messageId = req.getPayload().getContainer().getMessageTs();
    BlockActionPayload.User slackUser = req.getPayload().getUser();

    GameRoom gameRoom = dao.getGameRoom(Integer.valueOf(gameRoomId));
    EstimationScale estimationScale = dao.getEstimationScale(gameRoom.getEstimationScaleId());
    // ?????????? ???????????? ???????? ???????????? ????
    // ?? ???????????? ?????? ?????????? ?????????? ????????,
    // ?? ?????????????????? ???????? ???????????? ???????????? ???????????? ???????? ????????
    Task currentTask = dao.getCurrentTask(Integer.valueOf(gameRoomId));
    currentTask.setMessageId(messageId);
    dao.updateTaskField(currentTask.getId(), "message_id", messageId);

    dao.saveHistoryLog(new HistoryLog(gameRoom.getId(), slackUser.getId(),
        "%s ???????????????? ???????????? %s".formatted(slackUser.getName(), currentTask.getTitle())));
    updateTaskMessage(currentTask, estimationScale.getMarks(), channelId, List.of());

    return ctx.ack();
  }

  public Response handlePollSelect(BlockActionRequest req, ActionContext ctx) throws SlackApiException, IOException {
    BlockActionPayload.Channel slackChannel = req.getPayload().getChannel();
    String threadId = req.getPayload().getMessage().getThreadTs();
    BlockActionPayload.User slackUser = req.getPayload().getUser();
    String selected = req.getPayload().getActions().get(0).getValue();

    GameRoom gameRoom = dao.getGameRoomBySlackThreadId(threadId);
    EstimationScale estimationScale = dao.getEstimationScale(gameRoom.getEstimationScaleId());
    Task currentTask = dao.getCurrentTask(gameRoom.getId());
    List<TaskEstimation> taskEstimations = dao.getTaskEstimations(currentTask.getId());

    Optional<TaskEstimation> prevMarkOpt = taskEstimations.stream()
        .filter(est -> slackUser.getUsername().equals(est.getSlackUserName()))
        .findFirst();

    if (prevMarkOpt.isPresent()) {
      dao.updateTaskEstimation(prevMarkOpt.get(), selected);
    } else {
      TaskEstimation estimation = dao.saveTaskEstimation(currentTask.getId(), slackUser.getUsername(), selected);
      taskEstimations.add(estimation);
    }

    updateTaskMessage(currentTask, estimationScale.getMarks(), slackChannel.getId(), taskEstimations);

    dao.saveHistoryLog(new HistoryLog(gameRoom.getId(), slackUser.getId(),
        "%s ????????????????????????".formatted(slackUser.getName())));

    return ctx.ack();
  }

  public Response handlePollStop(BlockActionRequest req, ActionContext ctx) throws IOException, SlackApiException {
    BlockActionPayload.Channel slackChannel = req.getPayload().getChannel();
    BlockActionPayload.User slackUser = req.getPayload().getUser();
    String gameRoomId = req.getPayload().getActions().get(0).getValue();

    GameRoom gameRoom = dao.getGameRoom(Integer.valueOf(gameRoomId));
    Task currentTask = dao.getCurrentTask(gameRoom.getId());
    List<TaskEstimation> taskEstimations = dao.getTaskEstimations(currentTask.getId());

    currentTask.setStopped(true);
    dao.updateTaskField(currentTask.getId(), "stopped", true);
    updateTaskMessage(currentTask, List.of(), slackChannel.getId(), taskEstimations);

    dao.saveHistoryLog(new HistoryLog(gameRoom.getId(), slackUser.getId(),
        "%s ?????????????????? ??????????????????????".formatted(slackUser.getName())));

    return ctx.ack();
  }

  public Response handleTaskDone(BlockActionRequest req, ActionContext ctx) throws IOException, SlackApiException {
    BlockActionPayload.Channel slackChannel = req.getPayload().getChannel();
    String slackThreadId = req.getPayload().getMessage().getThreadTs();
    BlockActionPayload.User slackUser = req.getPayload().getUser();
    String finalMark = req.getPayload().getActions().get(0).getValue();

    GameRoom gameRoom = dao.getGameRoomBySlackThreadId(slackThreadId);
    Task currentTask = dao.getCurrentTask(gameRoom.getId());
    List<TaskEstimation> taskEstimations = dao.getTaskEstimations(currentTask.getId());
    EstimationScale estimationScale = dao.getEstimationScale(gameRoom.getEstimationScaleId());

    if (!estimationScale.getMarks().contains(finalMark)) {
      slackHelper.postSlackMessageEphemeral(slackChannel.getId(), slackThreadId, slackUser.getId(),
          "?????????? ???????????? ?????? ?? ?????????? (%s)".formatted(estimationScale.getMarks()));

      return ctx.ack();
    }

    currentTask.setFinalMark(finalMark);
    dao.updateTaskField(currentTask.getId(), "final_mark", finalMark);
    updateTaskMessage(currentTask, List.of(), slackChannel.getId(), taskEstimations);

    dao.saveHistoryLog(new HistoryLog(gameRoom.getId(), slackUser.getId(),
        "%s ???????????????? ???????????? ?? ?????????????? %s".formatted(slackUser.getName(), finalMark)));

    prepareNextTask(gameRoom, slackChannel.getId(), slackThreadId);

    return ctx.ack();
  }

  public void prepareNextTask(GameRoom gameRoom,
                              String slackChannelId,
                              String threadId) throws SlackApiException, IOException {
    Task currentTask = dao.getCurrentTask(gameRoom.getId());
    if (currentTask != null) {
      slackHelper.postSlackMessage(slackChannelId, threadId, "???????????? ????????????", makeNextTaskButtonBody(gameRoom.getId()));
    } else {
      List<Task> tasks = dao.getGameRoomTasks(gameRoom.getId());
      gameRoom.setEnded(true);
      dao.updateGameRoomField(gameRoom.getId(), "ended", true);
      String blocks = makeGameRoomEndedBody(gameRoom, tasks);
      slackHelper.postSlackMessage(slackChannelId, threadId, "????????????????????", blocks);
    }
  }

  private String makeNextTaskButtonBody(Integer gameRoomId) {
    return "[" +
        slackHelper.makeButtonBlock("?????????????????? ????????????", String.valueOf(gameRoomId), NEXT_TASK_EVENT) +
        "]";
  }

  public String makeGameRoomEndedBody(GameRoom gameRoom, List<Task> tasks) {
    String roomResults = tasks.stream()
        .map(t -> t.getTitle() + " - " + t.getFinalMark())
        .collect(Collectors.joining("\n"));

    Optional<Float> sumStoryPointsOpt = jiraHelper.makeSumStoryPoints(tasks);
    String sumStoryPoints = sumStoryPointsOpt.map(s -> " (" + s + ")").orElse("");

    String jiraButton = "";
    if (!"stub".equals(gameRoom.getPortfolioKey()))  {
      jiraButton = slackHelper.makeButtonBlock("?????????????????? ?? ????????", String.valueOf(gameRoom.getId()),
          CREATE_JIRA_ISSUE_EVENT);
    }

    return "[" +
        slackHelper.makePlainTextBlock("????????????????????%s:".formatted(sumStoryPoints)) + "," +
        slackHelper.makePlainTextBlock(roomResults) + "," +
        jiraButton +
        "]";
  }

  // ???????? ?????????? ?????????? ???????????? ?????? ?????????? ???? ???????????? ???????????????? ????????????, ?????? ???????? ???????????????? ??????????????????
  // ?????????????? ?????????? ???????? ?? ?????????? ??????????????????
  public void updateTaskMessage(Task task, List<String> marks, String channelId, List<TaskEstimation> taskEstimations) {
    if (task.getDeleted()) {
      // 0 ??????????????
      String updBlocks = "[" + slackHelper.makePlainTextBlock("(??????????????) " + task.getTitle()) + "]";
      slackHelper.updateSlackMessage(channelId, task.getMessageId(), "??????????????????????", updBlocks);
    } else if (!task.getStopped() && taskEstimations.size() == 0) {
      // 1 ???????????? ????????????????, ?????? ?????????? ???? ??????????????????
      String updBlocks = makePollStartedBody(task.getGameRoomId(), task.getTitle(), marks);
      slackHelper.updateSlackMessage(channelId, task.getMessageId(), "??????????????????????", updBlocks);
    } else if (!task.getStopped() && taskEstimations.size() > 0) {
      // 2 ????????????????????, ???????? ????????????
      List<String> usersDone = taskEstimations.stream()
          .map(TaskEstimation::getSlackUserName).collect(Collectors.toList());
      String updBlocks = malePollUpdatedBody(task.getGameRoomId(), task.getTitle(), marks, usersDone);
      slackHelper.updateSlackMessage(channelId, task.getMessageId(), "??????????????????????", updBlocks);
    } else if (task.getStopped() && task.getFinalMark() == null) {
      // 3 ?????????????????????? ??????????????????????, ???????????????????? ?????????????????? ????????????
      String blocks = makePollEndedBody(task.getTitle(), taskEstimations);
      slackHelper.updateSlackMessage(channelId, task.getMessageId(), "??????????????????????", blocks);
    } else if (task.getStopped() && task.getFinalMark() != null) {
      // 4 ?????????????????????? ??????????????????
      String blocks = makeTaskEndedBody(task.getTitle(), taskEstimations, task.getFinalMark());
      slackHelper.updateSlackMessage(channelId, task.getMessageId(), "??????????????????????", blocks);
    }
  }

  private String makePollStartedBody(Integer gameRoomId, String taskTitle, List<String> marks) {
    return "[" +
        slackHelper.makePlainTextBlock(taskTitle) + "," +
        slackHelper.makeMarksBlock(marks, POLL_OPTION_SELECTED_EVENT) + "," +
        slackHelper.makeDividerBlock() + "," +
        slackHelper.makeButtonBlock("?????????????????? ??????????????????????", String.valueOf(gameRoomId), POLL_ENDED_EVENT) +
        "]";
  }

  private String malePollUpdatedBody(Integer gameRoomId, String taskTitle, List<String> marks, List<String> users) {
    return "[" +
        slackHelper.makePlainTextBlock(taskTitle) + "," +
        slackHelper.makeMarksBlock(marks, POLL_OPTION_SELECTED_EVENT) + "," +
        slackHelper.makeDividerBlock() + "," +
        slackHelper.makePlainTextBlock("???????????? (" + users.size() + "):") + "," +
        slackHelper.makePlainTextMultilineBlock(users) + "," +
        slackHelper.makeButtonBlock("?????????????????? ??????????????????????", String.valueOf(gameRoomId), POLL_ENDED_EVENT) +
        "]";
  }

  private String makePollEndedBody(String taskTitle, List<TaskEstimation> estimations) {
    List<String> pollResults = estimations.stream()
        .map(e -> e.getSlackUserName() + " " + e.getMark())
        .collect(Collectors.toList());

    return "[" +
        slackHelper.makePlainTextBlock(taskTitle) + "," +
        slackHelper.makePlainTextBlock("????????????????????:") + "," +
        slackHelper.makePlainTextMultilineBlock(pollResults) + "," +
        slackHelper.makeInputBlock("???????????????? ????????????", TASK_ESTIMATED_EVENT) + "," +
        "]";
  }

  private String makeTaskEndedBody(String taskTitle, List<TaskEstimation> estimations, String finalMark) {
    List<String> pollResults = estimations.stream()
        .map(e -> e.getSlackUserName() + " " + e.getMark())
        .collect(Collectors.toList());

    return "[" +
        slackHelper.makePlainTextBlock(taskTitle) + "," +
        slackHelper.makePlainTextBlock("????????????????????:") + "," +
        slackHelper.makePlainTextMultilineBlock(pollResults) + "," +
        slackHelper.makePlainTextBlock("???????????????? ????????????: %s".formatted(finalMark)) + "," +
        "]";
  }
}
