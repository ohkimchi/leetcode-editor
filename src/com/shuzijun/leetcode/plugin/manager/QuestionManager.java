package com.shuzijun.leetcode.plugin.manager;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.JsonArray;
import com.shuzijun.leetcode.plugin.model.Question;
import com.shuzijun.leetcode.plugin.model.Tag;
import com.shuzijun.leetcode.plugin.setting.PersistentConfig;
import com.shuzijun.leetcode.plugin.utils.FileUtils;
import com.shuzijun.leetcode.plugin.utils.HttpClientUtils;
import com.shuzijun.leetcode.plugin.utils.URLUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * @author shuzijun
 */
public class QuestionManager {

    private final static Logger logger = LoggerFactory.getLogger(QuestionManager.class);

    private final static String ALLNAME = "all.json";

    private final static String TRANSLATIONNAME = "translation.json";


    public static List<Question> getQuestionService() {
        List<Question> questionList = null;

        HttpGet httpget = new HttpGet(URLUtils.getLeetcodeAll());
        CloseableHttpResponse response = HttpClientUtils.executeGet(httpget);
        if (response != null && response.getStatusLine().getStatusCode() == 200) {
            try {
                String body = EntityUtils.toString(response.getEntity(), "UTF-8");
                questionList = parseQuestion(body);
            } catch (IOException e1) {
                logger.error("获取题目内容错误", e1);
            }
        }
        httpget.abort();

        if (questionList != null && !questionList.isEmpty()) {
            String filePath = PersistentConfig.getInstance().getTempFilePath() + ALLNAME;
            FileUtils.saveFile(filePath, JSON.toJSONString(questionList));
        }
        return questionList;

    }

    public static List<Question> getQuestionCache() {
        String filePath = PersistentConfig.getInstance().getTempFilePath() + ALLNAME;
        String body = FileUtils.getFileBody(filePath);

        if (StringUtils.isBlank(body)) {
            return null;
        } else {
            return JSON.parseArray(body, Question.class);
        }
    }

    public static List<Tag> getTags() {
        List<Tag> tags = new ArrayList<>();

        HttpGet httpget = new HttpGet(URLUtils.getLeetcodeTags());
        CloseableHttpResponse response = HttpClientUtils.executeGet(httpget);
        if (response != null && response.getStatusLine().getStatusCode() == 200) {
            try {
                String body = EntityUtils.toString(response.getEntity(), "UTF-8");
                tags = parseTag(body);
            } catch (IOException e1) {
                logger.error("获取题目分类错误", e1);
            }
        } else {
            logger.error("获取题目分类网络错误");
        }
        httpget.abort();

        return tags;
    }


    private static List<Question> parseQuestion(String str) {

        List<Question> questionList = new ArrayList<Question>();

        if (StringUtils.isNotBlank(str)) {

            JSONArray jsonArray = JSONObject.parseObject(str).getJSONArray("stat_status_pairs");
            for (int i = 0; i < jsonArray.size(); i++) {
                JSONObject object = jsonArray.getJSONObject(i);
                Question question = new Question(object.getJSONObject("stat").getString("question__title"));
                question.setLeaf(Boolean.TRUE);
                question.setQuestionId(object.getJSONObject("stat").getString("question_id"));
                try {
                    question.setStatus(object.get("status") == null ? "" : object.getString("status"));
                } catch (Exception ee) {
                    question.setStatus("");
                }
                question.setTitleSlug(object.getJSONObject("stat").getString("question__title_slug"));
                question.setLevel(object.getJSONObject("difficulty").getInteger("level"));
                questionList.add(question);
            }

            translation(questionList);

            Collections.sort(questionList, new Comparator<Question>() {
                public int compare(Question arg0, Question arg1) {
                    return Integer.valueOf(arg0.getQuestionId()).compareTo(Integer.valueOf(arg1.getQuestionId()));
                }
            });
        }
        return questionList;

    }

    private static void translation(List<Question> questions) {

        if (URLUtils.getQuestionTranslation()) {

            String filePathTranslation = PersistentConfig.getInstance().getTempFilePath() + TRANSLATIONNAME;

            HttpPost translationPost = new HttpPost(URLUtils.getLeetcodeGraphql());
            try {
                String body = null;
                StringEntity entityCode = new StringEntity("{\"operationName\":\"getQuestionTranslation\",\"variables\":{},\"query\":\"query getQuestionTranslation($lang: String) {\\n  translations: allAppliedQuestionTranslations(lang: $lang) {\\n    title\\n    question {\\n      questionId\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"}");
                translationPost.setEntity(entityCode);
                translationPost.setHeader("Accept", "application/json");
                translationPost.setHeader("Content-type", "application/json");
                CloseableHttpResponse responseCode = HttpClientUtils.executePost(translationPost);
                if (responseCode != null && responseCode.getStatusLine().getStatusCode() == 200) {
                    body = EntityUtils.toString(responseCode.getEntity(), "UTF-8");
                    FileUtils.saveFile(filePathTranslation, body);
                } else {
                    body = FileUtils.getFileBody(filePathTranslation);
                }

                if (StringUtils.isNotBlank(body)) {
                    Map<String, String> translationMap = new HashMap<String, String>();
                    JSONArray jsonArray = JSONObject.parseObject(body).getJSONObject("data").getJSONArray("translations");
                    for (int i = 0; i < jsonArray.size(); i++) {
                        JSONObject object = jsonArray.getJSONObject(i);
                        translationMap.put(object.getJSONObject("question").getString("questionId"), object.getString("title"));
                    }
                    for (Question question : questions) {
                        question.setTitle(translationMap.get(question.getQuestionId()));
                    }
                } else {
                    logger.error("读取翻译内容为空");
                }

            } catch (IOException e1) {
                logger.error("获取题目翻译错误", e1);
            } finally {
                translationPost.abort();
            }

        }
    }


    private static List<Tag> parseTag(String str) {
        List<Tag> tags = new ArrayList<Tag>();

        if (StringUtils.isNotBlank(str)) {

            JSONArray jsonArray = JSONObject.parseObject(str).getJSONArray("topics");
            for (int i = 0; i < jsonArray.size(); i++) {
                JSONObject object = jsonArray.getJSONObject(i);
                Tag tag = new Tag();
                tag.setSlug(object.getString("slug"));
                String name = object.getString(URLUtils.getTagName());
                if (StringUtils.isBlank(name)) {
                    name = object.getString("name");
                }
                tag.setName(name);
                JSONArray questionArray = object.getJSONArray("questions");
                for (int j = 0; j < questionArray.size(); j++) {
                    tag.addQuestion(questionArray.getInteger(j));
                }
                Collections.sort(tag.getQuestions());
                tags.add(tag);
            }
        }
        return tags;
    }

}
