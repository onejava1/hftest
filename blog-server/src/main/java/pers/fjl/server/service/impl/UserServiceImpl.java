package pers.fjl.server.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import eu.bitwalker.useragentutils.UserAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import pers.fjl.common.constant.CommonConst;
import pers.fjl.common.constant.MessageConstant;
import pers.fjl.common.dto.*;
import pers.fjl.common.entity.QueryPageBean;
import pers.fjl.common.enums.LoginTypeEnum;
import pers.fjl.common.po.*;
import pers.fjl.common.po.admin.UserRole;
import pers.fjl.common.utils.JWTUtils;
import pers.fjl.common.vo.*;
import pers.fjl.server.dao.BlogDao;
import pers.fjl.server.dao.FavoritesDao;
import pers.fjl.server.dao.ThumbsUpDao;
import pers.fjl.server.dao.UserDao;
import pers.fjl.server.dao.admin.RoleDao;
import pers.fjl.server.dao.admin.TbUserRoleDao;
import pers.fjl.server.dto.UserDetailDTO;
import pers.fjl.server.exception.BizException;
import pers.fjl.server.face.FaceDetectUtils;
import pers.fjl.server.face.constant.FaceApiConfigProperties;
import pers.fjl.server.face.view.FaceParaVO;
import pers.fjl.server.face.view.FaceRegisterVO;
import pers.fjl.server.face.view.FaceResultVO;
import pers.fjl.server.service.CommentService;
import pers.fjl.server.service.OssService;
import pers.fjl.server.service.UserRoleService;
import pers.fjl.server.service.UserService;
import pers.fjl.server.strategy.context.SocialLoginStrategyContext;
import pers.fjl.server.utils.BeanCopyUtils;
import pers.fjl.server.utils.IpUtils;
import pers.fjl.server.utils.RedisUtil;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import static pers.fjl.common.constant.MessageConstant.USER_ABLE;
import static pers.fjl.common.constant.RabbitMQConst.EMAIL_EXCHANGE;
import static pers.fjl.common.constant.RedisConst.*;
import static pers.fjl.common.enums.LoginTypeEnum.FACE;
import static pers.fjl.common.enums.StatusCodeEnum.FACE_LOGIN_ERROR;
import static pers.fjl.server.utils.CommonUtils.checkEmail;
import static pers.fjl.server.utils.CommonUtils.getRandomCode;

/**
 * <p>
 * ?????????????????????
 * </p>
 *
 * @author fangjiale
 * @since 2021-01-26
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserDao, User> implements UserService {
    @Resource
    private OssService ossService;
    @Resource
    private UserDetailsServiceImpl userDetailsService;
    @Resource
    private HttpServletRequest request;
    @Resource
    private UserService userService;
    @Resource
    private CommentService commentService;
    @Resource
    private UserRoleService userRoleService;
    @Resource
    private ThumbsUpDao thumbsUpDao;
    @Resource
    private FavoritesDao favoritesDao;
    @Resource
    private UserDao userDao;
    @Resource
    private BlogDao blogDao;
    @Resource
    private RoleDao roleDao;
    @Resource
    private TbUserRoleDao tbUserRoleDao;
    @Resource
    private BCryptPasswordEncoder encoder;
    @Resource
    private RedisUtil redisUtil;
    @Resource
    private SessionRegistry sessionRegistry;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private SocialLoginStrategyContext socialLoginStrategyContext;

    public boolean UserExist(String username) {//???????????????????????????
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.select("1");
        wrapper.eq("username", username).last("limit 1");
        return userDao.selectCount(wrapper) != 0;
    }

    @Cacheable(value = {"UserMap"}, key = "#userId")
    public User findById(Long userId) {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("uid", userId);
        if (userDao.selectOne(wrapper) == null) {
            return null;
        }
        return userDao.selectOne(wrapper);
    }

    @Transactional
    @CacheEvict(value = {"UserListMap"})
    public boolean add(User user) {
        log.info("addUser.user.getUsername():[{}]", user.getUsername());
        log.info("addUser.user.getPassword():[{}]", user.getPassword());
        if (userService.UserExist(user.getUsername())) {
            return false;
        }
        Long uid = IdWorker.getId(User.class);
        user.setUid(uid);
        user.setStatus(MessageConstant.USER_ABLE);
        user.setPassword(encoder.encode(user.getPassword()));
        user.setAvatar(isImagesTrue(user.getAvatar()));
        userDao.insert(user);
        UserRole userRole = new UserRole();
        userRole.setRid(2);
        userRole.setUid(uid);
        tbUserRoleDao.insert(userRole);//??????????????????
        return true;
    }

    /**
     * ??????????????????????????????????????????????????????
     *
     * @param postUrl ?????????????????????url
     * @return url
     */
    public String isImagesTrue(String postUrl) {
        if (postUrl.contains("tcefrep.oss-cn-beijing.aliyuncs.com")) {   //?????????oss???????????????????????????????????????
            return postUrl;
        }
        int max = 1000;
        int min = 1;
        String picUrl = "https://unsplash.it/100/100?image=";
        try {
            URL url = new URL(postUrl);
            HttpURLConnection urlCon = (HttpURLConnection) url.openConnection();
            urlCon.setRequestMethod("POST");
            urlCon.setRequestProperty("Content-type",
                    "application/x-www-form-urlencoded");
            if (urlCon.getResponseCode() == HttpURLConnection.HTTP_OK) {
                return postUrl;
            } else {
                Random random = new Random();
                int s = random.nextInt(max) % (max - min + 1) + min;
                return picUrl + s;
            }
        } catch (Exception e) {   // ????????????????????????
            Random random = new Random();
            int s = random.nextInt(max) % (max - min + 1) + min;
            return picUrl + s;
        }
    }

    @Override
    public boolean verifyCode(String verKey, String code) {
        String realCode = (String) redisUtil.get(USER_CODE_KEY + verKey);
        redisUtil.del(USER_CODE_KEY + verKey);  // ????????????????????????????????????????????????????????????????????????redis???????????????
        if (code == null || StringUtils.isEmpty(code)) {
            throw new AuthenticationServiceException("?????????????????????");
        }
        if (realCode == null || StringUtils.isEmpty(realCode) || !code.equalsIgnoreCase(realCode)) {
            throw new AuthenticationServiceException("??????????????????????????????");
        }
        return true;
    }

    @CacheEvict(value = {"UserMap"}, key = "#updateUserVO.getUid()")
    public boolean updateUser(UpdateUserVO updateUserVO) {
        String realCode = (String) redisUtil.get(USER_CODE_KEY + updateUserVO.getEmail());    // ????????????????????????????????????
        if (!realCode.equals(updateUserVO.getCode())) {
            throw new BizException("????????????????????????????????????");
        }
        User userDB = userDao.selectById(updateUserVO.getUid());
        if (userService.UserExist(updateUserVO.getUsername()) && !userDB.getUsername().equals(updateUserVO.getUsername())) {
            return false;
        }
        if (updateUserVO.getPassword() != null && !updateUserVO.getPassword().equals("")) { // ?????????????????????
            updateUserVO.setPassword(encoder.encode(updateUserVO.getPassword()));
        } else {
            updateUserVO.setPassword(null);
        }
        BeanCopyUtils.copyPropertiesIgnoreNull(updateUserVO, userDB);
        userDB.setUpdateTime(LocalDateTime.now());
        userDB.setStatus(MessageConstant.USER_ABLE);
        userDB.setLoginType(updateUserVO.getLoginType());
        userDB.setAvatar(isImagesTrue(updateUserVO.getAvatar()));
        userDao.updateById(userDB);
        redisUtil.del(USER_CODE_KEY + updateUserVO.getEmail());
        return true;
    }

    @Cacheable(value = {"UserListMap"})
    public List<User> getUserList() {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.select("uid", "username", "nickname", "avatar")
                .orderByAsc("create_time");
        return userDao.selectList(wrapper);
    }

    @Override
    public User selectByUsername(String username) {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("username", username)
                .select("username", "nickname", "avatar", "uid");
        return userDao.selectOne(wrapper);
    }

    @Override
    public Page<UserBackDTO> adminUser(QueryPageBean queryPageBean) {
//        //??????????????????????????????user???page????????????????????????UserBackDTO???page?????????
//        Page<User> userPage = new Page<>(queryPageBean.getCurrentPage(), queryPageBean.getPageSize());
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.like(queryPageBean.getQueryString() != null, "nickname", queryPageBean.getQueryString());
//        Page<User> userResultPage = userDao.selectPage(userPage, wrapper);
//        List<UserBackDTO> userBackDTOList = BeanCopyUtils.copyList(userResultPage.getRecords(), UserBackDTO.class);
        Page<UserBackDTO> userBackDTOPage = new Page<>();
        userBackDTOPage.setTotal(userDao.selectCount(wrapper));
        userBackDTOPage.setRecords(userDao.adminUser(queryPageBean));
        return userBackDTOPage;
    }

    public User login(User user) {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.select("uid", "username", "password", "status", "nickname", "avatar");
        wrapper.eq("username", user.getUsername());
        //???????????????
        User login_user = userDao.selectOne(wrapper);
        log.debug("login_user:[{}]", login_user.toString());
        if (!encoder.matches(user.getPassword(), login_user.getPassword())) {
            throw new BizException("??????????????????????????????????????????");
        }
        if (login_user.isStatus() == (MessageConstant.USER_DISABLE)) {
            throw new BizException("??????????????????,????????????");
        }
        update(new UpdateWrapper<User>()
                .set("last_ip", user.getLastIp())
                .eq("username", login_user.getUsername()));

        return login_user;
    }

    /**
     * ??????????????????
     */
    public void statisticalUserArea() {
        // ????????????????????????
        Map<String, Long> userAreaMap = userDao.selectList(new LambdaQueryWrapper<User>().select(User::getIpSource))
                .stream()
                .map(item -> {
                    if (com.baomidou.mybatisplus.core.toolkit.StringUtils.isNotBlank(item.getIpSource())) {
                        return item.getIpSource().substring(0, 2)
                                .replaceAll(CommonConst.PROVINCE, "")
                                .replaceAll(CommonConst.CITY, "");
                    }
                    return CommonConst.UNKNOWN;
                })
                .collect(Collectors.groupingBy(item -> item, Collectors.counting()));
        // ????????????
        List<UserAreaDTO> userAreaList = userAreaMap.entrySet().stream()
                .map(item -> UserAreaDTO.builder()
                        .name(item.getKey())
                        .value(item.getValue())
                        .build())
                .collect(Collectors.toList());
        redisUtil.set(USER_AREA, JSON.toJSONString(userAreaList));
    }

    @Override
    public List<UserAreaDTO> listUserAreas() {
        userService.statisticalUserArea();
        List<UserAreaDTO> userAreaDTOList = new ArrayList<>();
        // ??????????????????????????????
        Object userArea = redisUtil.get(USER_AREA);
        if (Objects.nonNull(userArea)) {
            userAreaDTOList = JSON.parseObject(userArea.toString(), List.class);
        }
        return userAreaDTOList;
    }

    @Override
    public UserDetailDTO getUserDetail(String username, HttpServletRequest request, String ipAddress, String ipSource) {

        User user = userDao.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (Objects.isNull(user)) {
            throw new AuthenticationServiceException(MessageConstant.USER_NOT_EXIST);
        }
        // ??????????????????
        List<String> roleList = roleDao.listRolesByUid(user.getUid());
        UserAgent userAgent = IpUtils.getUserAgent(request);
        return UserDetailDTO.builder()
                .uid(user.getUid())
                .username(user.getUsername())
                .password(user.getPassword())
                .email(user.getEmail())
                .roleList(roleList)
                .loginType(user.getLoginType())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .lastIp(ipAddress)
                .ipSource(ipSource)
                .status(user.isStatus())
                .browser(userAgent.getBrowser().getName())
                .os(userAgent.getOperatingSystem().getName())
                .lastLoginTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")))
                .build();
    }

    @CacheEvict(value = {"UserListMap"})
    @Override
    public void delete(List<Long> uidList) {
        // ???????????????????????????????????????
        blogDao.delete(new LambdaQueryWrapper<Blog>().in(Blog::getUid, uidList));
        // ??????????????????????????????
        commentService.remove(new LambdaQueryWrapper<Comment>().in(Comment::getUid, uidList));
        // ???????????????????????????
        userRoleService.remove(new LambdaQueryWrapper<UserRole>().in(UserRole::getUid, uidList));
        // ???????????????????????????
        thumbsUpDao.delete(new LambdaQueryWrapper<ThumbsUp>().in(ThumbsUp::getUid, uidList));
        //???????????????????????????
        favoritesDao.delete(new LambdaQueryWrapper<Favorites>().in(Favorites::getUid, uidList));
        //????????????
        userDao.deleteBatchIds(uidList);
    }

    @Override
    public void updateUserDisable(UserDisableVO userDisableVO) {
        // ????????????????????????
        User user = User.builder()
                .uid(userDisableVO.getUid())
                .status(userDisableVO.getStatus().equals(1))
                .build();
        userDao.updateById(user);
    }

    @Override
    public void sendCode(String email) {
        // ????????????????????????
        if (!checkEmail(email)) {
            throw new BizException("?????????????????????");
        }
        // ?????????????????????????????????
        String code = getRandomCode();
        // ???????????????
        EmailDTO emailDTO = EmailDTO.builder()
                .email(email)
                .subject("?????????")
                .content("?????????????????? " + code + " ?????????30????????????????????????????????????")
                .build();

        rabbitTemplate.convertAndSend(EMAIL_EXCHANGE, null, new Message(JSON.toJSONBytes(emailDTO), new MessageProperties()));
        // ??????????????????redis????????????????????????30??????
        redisUtil.set(USER_CODE_KEY + email, code, CODE_EXPIRE_TIME);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void resetPassword(ResetPasswordVO resetPasswordVO) {
        User userDB = userDao.selectOne(new LambdaQueryWrapper<User>().select(User::getUid, User::getEmail).eq(User::getUsername, resetPasswordVO.getUsername()));
        if (userDB == null) {
            throw new BizException("????????????????????????????????????");
        }
        if (!userDB.getEmail().equals(resetPasswordVO.getEmail())) { // ???????????????????????????
            throw new BizException("??????????????????????????????????????????");
        }
        String realCode = (String) redisUtil.get(USER_CODE_KEY + resetPasswordVO.getEmail());    // ????????????????????????????????????
        if (!realCode.equals(resetPasswordVO.getCode())) {
            throw new BizException("????????????????????????????????????");
        }
        User user = new User();
        user.setUid(userDB.getUid());
        user.setStatus(MessageConstant.USER_ABLE);
        user.setPassword(encoder.encode(resetPasswordVO.getPassword()));
        user.setUpdateTime(LocalDateTime.now());
        userDao.updateById(user);
    }

    @Override
    public Page<UserOnlineDTO> listOnlineUsers(QueryPageBean queryPageBean) {
        // ??????security??????session
        List<UserOnlineDTO> userOnlineDTOList = sessionRegistry.getAllPrincipals().stream()
                .filter(item -> sessionRegistry.getAllSessions(item, false).size() > 0)
                .map(item -> JSON.parseObject(JSON.toJSONString(item), UserOnlineDTO.class))
                .filter(item -> com.baomidou.mybatisplus.core.toolkit.StringUtils.isBlank(queryPageBean.getQueryString()) || item.getNickname().contains(queryPageBean.getQueryString()))
                .sorted(Comparator.comparing(UserOnlineDTO::getLastLoginTime).reversed())
                .collect(Collectors.toList());

        Page<UserOnlineDTO> userOnlineDTOPage = new Page<>();
        // ????????????
        int fromIndex = ((queryPageBean.getCurrentPage() - 1) * queryPageBean.getPageSize());
        int size = queryPageBean.getPageSize();
        int toIndex = userOnlineDTOList.size() - fromIndex > size ? fromIndex + size : userOnlineDTOList.size();
        List<UserOnlineDTO> userOnlineList = userOnlineDTOList.subList(fromIndex, toIndex);
        userOnlineDTOPage.setRecords(userOnlineList);
        userOnlineDTOPage.setTotal(userOnlineDTOList.size());
        return userOnlineDTOPage;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void removeOnlineUser(Long uid) {
        // ????????????session
        List<Object> userIdList = sessionRegistry.getAllPrincipals().stream().filter(item -> {
            UserDetailDTO userDetailDTO = (UserDetailDTO) item;
            return userDetailDTO.getUid().equals(uid);
        }).collect(Collectors.toList());
        List<SessionInformation> allSessions = new ArrayList<>();
        userIdList.forEach(item -> allSessions.addAll(sessionRegistry.getAllSessions(item, false)));
        // ??????session
        allSessions.forEach(SessionInformation::expireNow);
        redisUtil.del(TOKEN_ALLOW_LIST + uid);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public UserInfoDTO qqLogin(QQLoginVO qqLoginVO) {
        return socialLoginStrategyContext.executeLoginStrategy(JSON.toJSONString(qqLoginVO), LoginTypeEnum.QQ);
    }

    @Transactional(rollbackFor = BizException.class)
    @Override
    public UserInfoDTO weiboLogin(WeiboLoginVO weiboLoginVO) {
        return socialLoginStrategyContext.executeLoginStrategy(JSON.toJSONString(weiboLoginVO), LoginTypeEnum.WEIBO);
    }

    @Override
    public UserInfoDTO faceLogin(FaceRegisterVO faceRegisterVO, HttpServletRequest request) throws IOException {
        FaceParaVO faceParaVO = FaceParaVO.builder().faceImgUrl(faceRegisterVO.getFaceImgUrl())
                .groupId(FaceApiConfigProperties.GROUP_ID).build();
        FaceResultVO faceSearchResult = FaceDetectUtils.faceSearch(faceParaVO); // ??????????????????????????????????????????
        Double score = faceSearchResult.getUserList().get(0).getScore();
        if (score > 80.0) {   // ???????????????80%???????????????????????????
            String userId = faceSearchResult.getUserList().get(0).getUserId();
            User user = userDao.selectById(Long.parseLong(userId));
            // ????????????ip??????
            String ipAddress = IpUtils.getIpAddr(request);
            String ipSource = IpUtils.getIpSource(ipAddress);
            // ???????????????????????????
            UserDetailDTO userDetailDTO = getUserDetail(user, ipAddress, ipSource);
            // ????????????????????????
            if (!userDetailDTO.isStatus()) {
                throw new BizException("??????????????????");
            }
            // ?????????????????????springSecurity??????
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userDetailDTO, null, userDetailDTO.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);
            //  ?????????????????????????????????????????????????????????token??????session
            sessionRegistry.registerNewSession(request.getSession().getId(), auth.getPrincipal());
            return BeanCopyUtils.copyObject(userDetailDTO, UserInfoDTO.class);
        } else {
            throw new BizException("????????????????????????????????????????????????????????????????????????" + score + "%?????????80%?????????????????????????????????", FACE_LOGIN_ERROR);
        }
    }

    @Override
    public UserInfoDTO facePhotoLogin(MultipartFile file, HttpServletRequest request) throws IOException {
        String imageUrl = ossService.uploadImg(file, "face/camera");
        FaceRegisterVO faceRegisterVO = new FaceRegisterVO();
        faceRegisterVO.setFaceImgUrl(imageUrl);
        return faceLogin(faceRegisterVO, request);
    }


    @Override
    public String getToken(UserInfoDTO userInfoDTO) {
        HashMap<String, String> payload = new HashMap<>();
        payload.put("id", String.valueOf(userInfoDTO.getUid()));
        payload.put("lastIp", userInfoDTO.getLastIp());
        payload.put("username", userInfoDTO.getUsername());
        return JWTUtils.getToken(payload);
    }

    @Override
    @Transactional
    public boolean registerByFace(FaceRegisterVO faceRegisterVO) throws IOException {
        if (StringUtils.isEmpty(faceRegisterVO.getFaceImgUrl())) {
            throw new BizException("????????????????????????");
        }
        Long uid = IdWorker.getId(User.class);
        FaceParaVO faceParaVO = FaceParaVO.builder().faceImgUrl(faceRegisterVO.getFaceImgUrl())
                .groupId(FaceApiConfigProperties.GROUP_ID).build();
        FaceResultVO faceSearchResult = FaceDetectUtils.faceSearch(faceParaVO); // ??????????????????????????????????????????
        Double score = faceSearchResult.getUserList().get(0).getScore();
        if (score > 95.0) {   // ??????????????????????????????????????????????????????????????????
            throw new BizException("????????????????????????????????????????????????????????????????????????" + score + "%????????????????????????");
        }
        faceParaVO.setUserId(String.valueOf(uid));
        FaceResultVO faceResultVO = FaceDetectUtils.faceAdd(faceParaVO);// ??????api??????????????????
        User user = User.builder().uid(uid).password(faceResultVO.getFaceToken())
                .avatar(isImagesTrue("")).username(String.valueOf(uid))
                .nickname(String.valueOf(uid)).status(true).loginType(FACE.getType()).build();
        userDao.insert(user);
        UserRole userRole = UserRole.builder()
                .uid(user.getUid())
                .rid(2)
                .build();
        tbUserRoleDao.insert(userRole);
        return true;
    }

    /**
     * ??????????????????
     *
     * @param user      ????????????
     * @param ipAddress ip??????
     * @param ipSource  ip???
     * @return {@link UserDetailDTO} ????????????
     */
    public UserDetailDTO getUserDetail(User user, String ipAddress, String ipSource) {
        // ??????????????????
        userDao.update(new User(), new LambdaUpdateWrapper<User>()
                .set(User::getLastIp, ipAddress)
                .set(User::isStatus, USER_ABLE)
                .set(User::getIpSource, ipSource)
                .eq(User::getUid, user.getUid()));
        // ????????????
        return userDetailsService.convertUserDetail(user, request);
    }
}
