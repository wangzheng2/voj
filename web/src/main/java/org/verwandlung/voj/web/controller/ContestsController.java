package org.verwandlung.voj.web.controller;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import com.alibaba.fastjson.JSON;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.verwandlung.voj.web.exception.ResourceNotFoundException;
import org.verwandlung.voj.web.model.*;
import org.verwandlung.voj.web.service.ContestService;
import org.verwandlung.voj.web.util.CsrfProtector;
import org.verwandlung.voj.web.util.HttpRequestParser;
import org.verwandlung.voj.web.util.HttpSessionParser;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 处理竞赛的相关请求.
 * 
 * @author Haozhe Xie
 */
@Controller
@RequestMapping(value="/contest")
public class ContestsController {
	/**
	 * 显示竞赛列表页面.
	 * @param keyword - 竞赛的关键词
	 * @param request - HttpRequest对象
	 * @param response - HttpResponse对象
	 * @return 一个包含竞赛列表页面内容的ModelAndView对象
	 */
	@RequestMapping(value="", method=RequestMethod.GET)
	public ModelAndView contestsView(
			@RequestParam(value="keyword", required = false) String keyword,
			HttpServletRequest request, HttpServletResponse response) {
		List<Contest> contests = contestService.getContests(keyword, 0, NUMBER_OF_CONTESTS_PER_PAGE);

		ModelAndView view = new ModelAndView("contests/contests");
		view.addObject("contests", contests);
		view.addObject("currentTime", new Date());
		return view;
	}
	
	/**
	 * 获取竞赛的列表.
	 * @param keyword - 竞赛的关键词
	 * @param startIndex - 当前加载的最后一条记录的索引值 (Index)
	 * @param request - HttpRequest对象
	 * @return 一个包含竞赛列表的HashMap对象
	 */
	@RequestMapping(value="/getContests.action", method=RequestMethod.GET)
	public @ResponseBody Map<String, Object> getContestsAction(
			@RequestParam(value="keyword", required=false) String keyword,
			@RequestParam(value="startIndex") long startIndex,
			HttpServletRequest request) {
		Map<String, Object> result = new HashMap<>(3, 1);

		List<Contest> contests = contestService.getContests(keyword, startIndex, NUMBER_OF_CONTESTS_PER_PAGE);
		result.put("isSuccessful", contests != null && !contests.isEmpty());
		result.put("contests", contests);

		return result;
	}
	
	/**
	 * 显示竞赛详细信息的页面.
	 * @param contestId - 竞赛的唯一标识符
	 * @param request - HttpRequest对象
	 * @param response - HttpResponse对象
	 * @return 包含提交详细信息的ModelAndView对象 
	 */
	@RequestMapping(value="/{contestId}", method=RequestMethod.GET)
	public ModelAndView contestView(
			@PathVariable("contestId") long contestId,
			HttpServletRequest request, HttpServletResponse response) {
		HttpSession session = request.getSession();
		User currentUser = HttpSessionParser.getCurrentUser(session);
		Contest contest = contestService.getContest(contestId);
		if ( contest == null ) {
			throw new ResourceNotFoundException();
		}

		boolean isAttended = contestService.isAttendContest(contestId, currentUser);
		long numberOfContestants = contestService.getNumberOfContestantsOfContest(contestId);
		List<Long> problemIdList = JSON.parseArray(contest.getProblems(), Long.class);
		List<Problem> problems = contestService.getProblemsOfContests(problemIdList);
		Map<Long, ContestSubmission> submissions = contestService.getSubmissionsOfContestantOfContest(contestId, currentUser);

		ModelAndView view = new ModelAndView("contests/contest");
		view.addObject("currentTime", new Date())
			.addObject("contest", contest)
			.addObject("problems", problems)
			.addObject("submissions", submissions)
			.addObject("isAttended", isAttended)
			.addObject("numberOfContestants", numberOfContestants)
			.addObject("csrfToken", CsrfProtector.getCsrfToken(request.getSession()));
		return view;
	}

	/**
	 * 处理用户参加竞赛的请求.
	 * @param contestId - 竞赛的唯一标识符
	 * @param csrfToken - 用于防止CSRF攻击的Token
	 * @param request - HttpRequest对象
	 * @param response - HttpResponse对象
	 * @return 包含是否成功参加竞赛状态信息的Map对象
	 */
	@RequestMapping(value="/{contestId}/attend.action", method=RequestMethod.POST)
	public @ResponseBody Map<String, Boolean> attendContestAction(
			@PathVariable("contestId") long contestId,
			@RequestParam(value="csrfToken") String csrfToken,
			HttpServletRequest request, HttpServletResponse response) {
		HttpSession session = request.getSession();
		String ipAddress = HttpRequestParser.getRemoteAddr(request);
		User currentUser = HttpSessionParser.getCurrentUser(session);
		boolean isCsrfTokenValid = CsrfProtector.isCsrfTokenValid(csrfToken, session);

		Map<String, Boolean> result = contestService.attendContest(contestId, currentUser, isCsrfTokenValid);
		if ( result.get("isSuccessful") ) {
			LOGGER.info(String.format("User: {%s} attended contest #%d at %s",
					new Object[] {currentUser, contestId, ipAddress}));
		}
		return result;
	}

	@RequestMapping(value="/{contestId}/leaderboard", method=RequestMethod.GET)
	public ModelAndView leaderboardView(
			@PathVariable("contestId") long contestId,
			HttpServletRequest request, HttpServletResponse response) {
		Contest contest = contestService.getContest(contestId);
		Date currentTime = new Date();
		if ( contest == null || contest.getStartTime().after(currentTime) ||
				!(contest.getContestMode().equals("OI") || contest.getContestMode().equals("ACM")) ) {
			throw new ResourceNotFoundException();
		}

		List<Long> problemIdList = JSON.parseArray(contest.getProblems(), Long.class);
		List<Problem> problems = contestService.getProblemsOfContests(problemIdList);
		ModelAndView view = null;
		Map<String, Object> result = null;

		if ( contest.getContestMode().equals("OI") ) {
			view = new ModelAndView("contests/leaderboard-oi");
			result = contestService.getLeaderBoardForOi(contestId);
		} else if ( contest.getContestMode().equals("ACM") ) {
			view = new ModelAndView("contests/leaderboard-acm");
			result = contestService.getLeaderBoardForAcm(contestId);
		}
		List<ContestContestant> contestants = (List<ContestContestant>) result.get("contestants");
		Map<Long, Map<Long, Submission>> submissions = (Map<Long, Map<Long, Submission>>) result.get("submissions");
		view.addObject("contestants", contestants);
		view.addObject("submissions", submissions);
		view.addObject("contest", contest);
		view.addObject("problems", problems);
		return view;
	}

	/**
	 * 每次查询需要加载竞赛的数量.
	 */
	private static final int NUMBER_OF_CONTESTS_PER_PAGE = 10;

	/**
	 * 自动注入的ContestService对象.
	 */
	@Autowired
	private ContestService contestService;

	/**
	 * 日志记录器.
	 */
	private static final Logger LOGGER = LogManager.getLogger(ContestsController.class);
}
