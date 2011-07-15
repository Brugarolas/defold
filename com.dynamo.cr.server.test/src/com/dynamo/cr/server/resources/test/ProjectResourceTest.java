package com.dynamo.cr.server.resources.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.ws.rs.core.UriBuilder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.dynamo.cr.client.ClientFactory;
import com.dynamo.cr.client.ClientUtils;
import com.dynamo.cr.client.IBranchClient;
import com.dynamo.cr.client.IClientFactory;
import com.dynamo.cr.client.IProjectClient;
import com.dynamo.cr.client.IUsersClient;
import com.dynamo.cr.client.RepositoryException;
import com.dynamo.cr.common.providers.ProtobufProviders;
import com.dynamo.cr.protocol.proto.Protocol;
import com.dynamo.cr.protocol.proto.Protocol.BranchList;
import com.dynamo.cr.protocol.proto.Protocol.BranchStatus;
import com.dynamo.cr.protocol.proto.Protocol.BranchStatus.Status;
import com.dynamo.cr.protocol.proto.Protocol.CommitDesc;
import com.dynamo.cr.protocol.proto.Protocol.Log;
import com.dynamo.cr.protocol.proto.Protocol.ProjectInfo;
import com.dynamo.cr.protocol.proto.Protocol.ResourceInfo;
import com.dynamo.cr.protocol.proto.Protocol.ResourceType;
import com.dynamo.cr.protocol.proto.Protocol.UserInfo;
import com.dynamo.cr.server.Server;
import com.dynamo.cr.server.model.ModelUtil;
import com.dynamo.cr.server.model.Project;
import com.dynamo.cr.server.model.User;
import com.dynamo.cr.server.test.Util;
import com.dynamo.cr.server.util.FileUtil;
import com.dynamo.server.git.CommandUtil;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;

public class ProjectResourceTest {

    private Server server;
    int port = 6500;

    String ownerEmail = "owner@foo.com";
    String ownerPassword = "secret";
    private User owner;
    private UserInfo ownerInfo;
    private IBranchClient ownerBranchClient;
    private IProjectClient ownerProjectClient;
    private IClientFactory ownerFactory;
    private WebResource ownerProjectsWebResource;

    private Project proj1;

    String memberEmail = "member@foo.com";
    String memberPassword = "secret";
    private User member;
    private UserInfo memberInfo;
    private WebResource memberProjectsWebResource;

    String nonMemberEmail = "nonmember@foo.com";
    String nonMemberPassword = "secret";
    private User nonMember;
    private UserInfo nonMemberInfo;
    private WebResource nonMemberProjectsWebResource;

    void execCommand(String command) throws IOException {
        CommandUtil.Result r = CommandUtil.execCommand(new String[] {"sh", command});
        if (r.exitValue != 0) {
            System.err.println(r.stdOut);
            System.err.println(r.stdErr);
        }
        assertEquals(0, r.exitValue);
    }

    void execCommand(String command, String arg) throws IOException {
        CommandUtil.Result r = CommandUtil.execCommand(new String[] {"/bin/bash", command, arg});
        if (r.exitValue != 0) {
            System.err.println(r.stdOut);
            System.err.println(r.stdErr);
        }
        assertEquals(0, r.exitValue);
    }

    @Before
    public void setUp() throws Exception {
        // "drop-and-create-tables" can't handle model changes correctly. We need to drop all tables first.
        // Eclipse-link only drops tables currently specified. When the model change the table set also change.
        File tmp_testdb = new File("tmp/testdb");
        if (tmp_testdb.exists()) {
            getClass().getClassLoader().loadClass("org.apache.derby.jdbc.EmbeddedDriver");
            Util.dropAllTables();
        }

        server = new Server("test_data/crepo_test.config");

        EntityManagerFactory emf = server.getEntityManagerFactory();
        EntityManager em = emf.createEntityManager();

        em.getTransaction().begin();

        owner = new User();
        owner.setEmail(ownerEmail);
        owner.setFirstName("undefined");
        owner.setLastName("undefined");
        owner.setPassword(ownerPassword);
        em.persist(owner);

        member = new User();
        member.setEmail(memberEmail);
        member.setFirstName("undefined");
        member.setLastName("undefined");
        member.setPassword(memberPassword);
        em.persist(member);

        nonMember = new User();
        nonMember.setEmail(nonMemberEmail);
        nonMember.setFirstName("undefined");
        nonMember.setLastName("undefined");
        nonMember.setPassword(nonMemberPassword);
        em.persist(nonMember);

        proj1 = ModelUtil.newProject(em, owner, "proj1", "proj1 description");
        em.getTransaction().commit();

        ClientConfig cc = new DefaultClientConfig();
        cc.getClasses().add(ProtobufProviders.ProtobufMessageBodyReader.class);
        cc.getClasses().add(ProtobufProviders.ProtobufMessageBodyWriter.class);

        URI uri;
        Client client;
        IUsersClient usersClient;

        uri = UriBuilder.fromUri(String.format("http://localhost/users")).port(port).build();

        client = Client.create(cc);
        client.addFilter(new HTTPBasicAuthFilter(ownerEmail, ownerPassword));
        ownerFactory = new ClientFactory(client);
        usersClient = ownerFactory.getUsersClient(uri);
        ownerInfo = usersClient.getUserInfo(ownerEmail);

        uri = UriBuilder.fromUri(String.format("http://localhost/projects/%d/%d", ownerInfo.getId(), proj1.getId())).port(port).build();
        ownerProjectClient = ownerFactory.getProjectClient(uri);
        ownerBranchClient = ownerFactory.getBranchClient(ClientUtils.getBranchUri(ownerProjectClient, "branch1"));
        ownerProjectsWebResource = client.resource(uri);

        client = Client.create(cc);
        client.addFilter(new HTTPBasicAuthFilter(memberEmail, memberPassword));
        memberInfo = usersClient.getUserInfo(memberEmail);
        uri = UriBuilder.fromUri(String.format("http://localhost/projects/%d/%d", memberInfo.getId(), proj1.getId())).port(port).build();
        memberProjectsWebResource = client.resource(uri);

        // Add member
        ownerProjectsWebResource.path("/members").post(memberEmail);

        client = Client.create(cc);
        client.addFilter(new HTTPBasicAuthFilter(nonMemberEmail, nonMemberPassword));
        nonMemberInfo = usersClient.getUserInfo(nonMemberEmail);
        uri = UriBuilder.fromUri(String.format("http://localhost/projects/%d/%d", nonMemberInfo.getId(), proj1.getId())).port(port).build();
        nonMemberProjectsWebResource = client.resource(uri);

        FileUtil.removeDir(new File(server.getBranchRoot()));

        execCommand("scripts/setup_testdata.sh", Long.toString(proj1.getId()));
    }

    @After
    public void tearDown() throws Exception {
        server.stop();
    }

    /*
     * Basic tests
     */

    @Test
    public void launchInfo() throws Exception {
        ownerProjectClient.getLaunchInfo();

        ClientResponse response = memberProjectsWebResource.path("/launch_info").get(ClientResponse.class);
        assertEquals(200, response.getStatus());

        response = nonMemberProjectsWebResource.path("/launch_info").get(ClientResponse.class);
        assertEquals(403, response.getStatus());
    }

    @Test
    public void applicationData() throws Exception {
        ClientResponse response = ownerProjectsWebResource.path("/application_data").queryParam("platform", "linux").get(ClientResponse.class);
        assertEquals(200, response.getStatus());

        response = memberProjectsWebResource.path("/application_data").queryParam("platform", "linux").get(ClientResponse.class);
        assertEquals(200, response.getStatus());

        response = ownerProjectsWebResource.path("/application_data").queryParam("platform", "wii").get(ClientResponse.class);
        assertEquals(404, response.getStatus());

        response = nonMemberProjectsWebResource.path("/application_data").queryParam("platform", "linux").get(ClientResponse.class);
        assertEquals(403, response.getStatus());
    }

    @Test
    public void applicationInfo() throws Exception {
        ClientResponse response = ownerProjectsWebResource.path("/application_info").queryParam("platform", "linux").get(ClientResponse.class);
        assertEquals(200, response.getStatus());

        response = memberProjectsWebResource.path("/application_info").queryParam("platform", "linux").get(ClientResponse.class);
        assertEquals(200, response.getStatus());

        response = ownerProjectsWebResource.path("/application_info").queryParam("platform", "wii").get(ClientResponse.class);
        assertEquals(404, response.getStatus());

        response = nonMemberProjectsWebResource.path("/application_info").queryParam("platform", "linux").get(ClientResponse.class);
        assertEquals(403, response.getStatus());
    }

    @Test
    public void addMember() throws Exception {
        ClientResponse response = nonMemberProjectsWebResource.path("/members").post(ClientResponse.class, nonMemberEmail);
        assertEquals(403, response.getStatus());

        response = memberProjectsWebResource.path("/members").post(ClientResponse.class, nonMemberEmail);
        assertEquals(403, response.getStatus());

        response = ownerProjectsWebResource.path("/members").post(ClientResponse.class, nonMemberEmail);
        assertEquals(204, response.getStatus());

        // Add again, verify the list is not increased
        int membersCount = ownerProjectClient.getProjectInfo().getMembersCount();
        assertEquals(3, membersCount);
        ownerProjectsWebResource.path("/members").post(nonMemberEmail);
        assertEquals(membersCount, ownerProjectClient.getProjectInfo().getMembersCount());

        response = ownerProjectsWebResource.path("/members").post(ClientResponse.class, "nonexisting@foo.com");
        assertEquals(404, response.getStatus());
    }

    @Test
    public void deleteMember() throws Exception {
        ClientResponse response = nonMemberProjectsWebResource.path(String.format("/members/%d", ownerInfo.getId())).delete(ClientResponse.class);
        assertEquals(403, response.getStatus());

        response = memberProjectsWebResource.path(String.format("/members/%d", ownerInfo.getId())).delete(ClientResponse.class);
        assertEquals(403, response.getStatus());

        assertEquals(2, ownerProjectClient.getProjectInfo().getMembersCount());
        response = ownerProjectsWebResource.path(String.format("/members/%d", memberInfo.getId())).delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
        assertEquals(1, ownerProjectClient.getProjectInfo().getMembersCount());

        response = ownerProjectsWebResource.path(String.format("/members/%d", memberInfo.getId())).delete(ClientResponse.class);
        assertEquals(404, response.getStatus());

        response = ownerProjectsWebResource.path("/members/9999").delete(ClientResponse.class);
        assertEquals(404, response.getStatus());
    }

    @Test
    public void projectInfo() throws Exception {
        ProjectInfo projectInfo = ownerProjectClient.getProjectInfo();
        assertEquals("proj1", projectInfo.getName());

        ClientResponse response = memberProjectsWebResource.path("/project_info").get(ClientResponse.class);
        assertEquals(200, response.getStatus());

        response = nonMemberProjectsWebResource.path("/project_info").get(ClientResponse.class);
        assertEquals(403, response.getStatus());
    }

    @Test
    public void simpleBenchMark() throws Exception {
        // Warm up the jit
        for (int i = 0; i < 2000; ++i) {
            ownerProjectClient.getLaunchInfo();
        }
        long start = System.currentTimeMillis();
        final int iterations = 1000;
        for (int i = 0; i < iterations; ++i) {
            ownerProjectClient.getLaunchInfo();
        }
        long end = System.currentTimeMillis();

        double elapsed = (end-start);
        System.out.format("simpleBenchMark: %f ms / request\n", elapsed / (iterations));
    }

    @Test(expected = RepositoryException.class)
    public void createBranchInvalidProject() throws Exception {
        URI uri = UriBuilder.fromUri("http://localhost/99999").port(port).build();
        ownerProjectClient = ownerFactory.getProjectClient(uri);
        ownerProjectClient.createBranch("branch1");
    }

    @Test
    public void createBranch() throws Exception {
        ownerProjectClient.createBranch("branch1");
        BranchStatus branch_status;

        branch_status = ownerProjectClient.getBranchStatus("branch1");
        assertEquals("branch1", branch_status.getName());
        branch_status = ownerBranchClient.getBranchStatus();
        assertEquals("branch1", branch_status.getName());

        BranchList list = ownerProjectClient.getBranchList();
        assertEquals("branch1", list.getBranchesList().get(0));

        ownerProjectClient.deleteBranch("branch1");
        list = ownerProjectClient.getBranchList();
        assertEquals(0, list.getBranchesCount());
    }

    @Test(expected = RepositoryException.class)
    public void createBranchTwice() throws Exception {
        ownerProjectClient.createBranch("branch1");
        ownerProjectClient.createBranch("branch1");
    }

    @Test(expected = RepositoryException.class)
    public void deleteNonExistantBranch() throws Exception {
        ownerProjectClient.deleteBranch("branch1");
    }

    @Test
    public void makeDirAddCommitUpdateRevertRemove() throws Exception {
        ownerProjectClient.createBranch("branch1");
        ownerBranchClient.mkdir("/content/foo");
        ownerBranchClient.mkdir("/content/foo");

        BranchStatus branch = ownerBranchClient.getBranchStatus();
        assertEquals(Protocol.BranchStatus.State.CLEAN, branch.getBranchState());

        ownerBranchClient.putResourceData("/content/foo/bar.txt", "bar data".getBytes());

        branch = ownerBranchClient.getBranchStatus();
        assertEquals(Protocol.BranchStatus.State.DIRTY, branch.getBranchState());

        assertEquals("bar data", new String(ownerBranchClient.getResourceData("/content/foo/bar.txt", "")));
        CommitDesc commit = ownerBranchClient.commit("message...");
        Log log = ownerBranchClient.log(1);
        assertEquals(commit.getId(), log.getCommits(0).getId());

        ownerBranchClient.putResourceData("/content/foo/bar.txt", "bar2 data".getBytes());
        assertEquals(Protocol.BranchStatus.State.DIRTY, branch.getBranchState());

        assertEquals("bar2 data", new String(ownerBranchClient.getResourceData("/content/foo/bar.txt", "")));
        assertEquals("bar data", new String(ownerBranchClient.getResourceData("/content/foo/bar.txt", "master")));

        ownerBranchClient.revertResource("/content/foo/bar.txt");
        assertEquals("bar data", new String(ownerBranchClient.getResourceData("/content/foo/bar.txt", "")));

        branch = ownerBranchClient.getBranchStatus();
        assertEquals(Protocol.BranchStatus.State.CLEAN, branch.getBranchState());

        ownerBranchClient.deleteResource("/content/foo/bar.txt");
        branch = ownerBranchClient.getBranchStatus();
        assertEquals(Protocol.BranchStatus.State.DIRTY, branch.getBranchState());

        ownerBranchClient.commit("message...");

        branch = ownerBranchClient.getBranchStatus();
        assertEquals(Protocol.BranchStatus.State.CLEAN, branch.getBranchState());
    }

    @Test
    public void getResourceNotFound() throws RepositoryException {
        ownerProjectClient.createBranch("branch1");
        try {
            ownerBranchClient.getResourceInfo("/content/does_not_exists");
            assertTrue(false);
        } catch (RepositoryException e) {
            assertEquals(404, e.getStatusCode());
            e.printStackTrace();
        }

        try {
            ownerBranchClient.getResourceData("/content/does_not_exists", "");
            assertTrue(false);
        } catch (RepositoryException e) {
            assertEquals(404, e.getStatusCode());
            e.printStackTrace();
        }

        try {
            ownerBranchClient.getResourceData("/content/does_not_exists", "does_not_exist");
            assertTrue(false);
        } catch (RepositoryException e) {
            assertEquals(404, e.getStatusCode());
            e.printStackTrace();
        }

        ownerBranchClient.putResourceData("/content/foo.txt", "foo".getBytes());
        assertEquals(Protocol.BranchStatus.State.DIRTY, ownerBranchClient.getBranchStatus().getBranchState());
        assertEquals("foo", new String(ownerBranchClient.getResourceData("/content/foo.txt", "")));
        try {
            ownerBranchClient.getResourceData("/content/foo.txt", "does_not_exist");
            assertTrue(false);
        } catch (RepositoryException e) {
            assertEquals(404, e.getStatusCode());
            e.printStackTrace();
        } finally {
            ownerBranchClient.deleteResource("/content/foo.txt");
            assertEquals(Protocol.BranchStatus.State.CLEAN, ownerBranchClient.getBranchStatus().getBranchState());
        }

        try {
            ownerBranchClient.getResourceData("/content/content", "");
            assertTrue(false);
        } catch (RepositoryException e) {
            assertEquals(404, e.getStatusCode());
            e.printStackTrace();
        }

        try {
            ownerBranchClient.getResourceData("/content/content", "does_not_exist");
            assertTrue(false);
        } catch (RepositoryException e) {
            assertEquals(404, e.getStatusCode());
            e.printStackTrace();
        }
    }

    @Test
    public void getResource() throws Exception {
        ownerProjectClient.createBranch("branch1");

        {
            String local_path = String.format("tmp/branch_root/%d/%d/branch1/content/file1.txt", proj1.getId(), ownerInfo.getId());
            long expected_size = new File(local_path).length();
            long expected_last_mod = new File(local_path).lastModified();

            ResourceInfo info = ownerBranchClient.getResourceInfo("/content/file1.txt");
            assertEquals(ResourceType.FILE, info.getType());
            assertEquals("file1.txt", info.getName());
            assertEquals("/content/file1.txt", info.getPath());
            assertEquals(expected_size, info.getSize());
            assertEquals(expected_last_mod, info.getLastModified());
        }

        {
            String local_path = String.format("tmp/branch_root/%d/%d/branch1/content/file1.txt", proj1.getId(), ownerInfo.getId());
            long expected_size = new File(local_path).length();

            byte[] data = ownerBranchClient.getResourceData("/content/file1.txt", "");
            String content = new String(data);
            assertEquals(expected_size, data.length);
            assertEquals("file1 data\n", content);
        }

        {
            try {
                ownerBranchClient.getResourceData("/content", "");
            } catch (RepositoryException e) {
                assertEquals(400, e.getStatusCode());
                e.printStackTrace();
            }
        }

        {
            String local_path = String.format("tmp/branch_root/%d/%d/branch1/content", proj1.getId(), ownerInfo.getId());
            long expected_last_mod = new File(local_path).lastModified();

            ResourceInfo info = ownerBranchClient.getResourceInfo("/content");
            assertEquals(ResourceType.DIRECTORY, info.getType());
            assertEquals("content", info.getName());
            assertEquals("/content", info.getPath());
            assertEquals(0, info.getSize());
            assertEquals(expected_last_mod, info.getLastModified());
            Set<String> expected_set = new HashSet<String>(Arrays.asList(new String[] { "file1.txt", "file2.txt", "test space.txt" }));
            Set<String> actual_set = new HashSet<String>(info.getSubResourceNamesList());
            assertEquals(expected_set, actual_set);
        }
    }

    @Test
    public void getResourceWithSpace() throws Exception {
        ownerProjectClient.createBranch("branch1");

        {
            ownerBranchClient.getResourceInfo("/content/test space.txt");
        }
    }

    /*
     * Branch tests. Update, commit, etc
     */

    @Test
    public void dirtyBranch() throws RepositoryException {
        ownerProjectClient.createBranch("branch1");

        // Check that branch is clean
        BranchStatus branch = ownerBranchClient.getBranchStatus();
        assertEquals(Protocol.BranchStatus.State.CLEAN, branch.getBranchState());

        byte[] old_data = ownerBranchClient.getResourceData("/content/file1.txt", "");

        // Update resource
        ownerBranchClient.putResourceData("/content/file1.txt", "new file1 data".getBytes());

        // Check that branch is dirty
        branch = ownerBranchClient.getBranchStatus();
        assertEquals(Protocol.BranchStatus.State.DIRTY, branch.getBranchState());

        // Update resource again with original data
        ownerBranchClient.putResourceData("/content/file1.txt", old_data);

        // Assert clean state
        branch = ownerBranchClient.getBranchStatus();
        assertEquals(Protocol.BranchStatus.State.CLEAN, branch.getBranchState());

        // Update resource again
        ownerBranchClient.putResourceData("/content/file1.txt", "new file1 data".getBytes());
        // Revert changes
        ownerBranchClient.revertResource("/content/file1.txt");
        // Assert clean state
        branch = ownerBranchClient.getBranchStatus();
        assertEquals(Protocol.BranchStatus.State.CLEAN, branch.getBranchState());

        // Create a new resource, delete later
        ownerBranchClient.putResourceData("/content/new_file.txt", "new file data".getBytes());

        // Check that branch is dirty
        branch = ownerBranchClient.getBranchStatus();
        assertEquals(Protocol.BranchStatus.State.DIRTY, branch.getBranchState());

        // Delete new resource
        ownerBranchClient.deleteResource("/content/new_file.txt");
        // Assert clean state
        branch = ownerBranchClient.getBranchStatus();
        assertEquals(Protocol.BranchStatus.State.CLEAN, branch.getBranchState());

        // Create a new resource, revert later
        ownerBranchClient.putResourceData("/content/new_file.txt", "new file data".getBytes());

        // Check that branch is dirty
        branch = ownerBranchClient.getBranchStatus();
        assertEquals(Protocol.BranchStatus.State.DIRTY, branch.getBranchState());

        // Rename file
        ownerBranchClient.renameResource("/content/new_file.txt", "/content/new_file2.txt");
        assertEquals(1, ownerBranchClient.getBranchStatus().getFileStatusList().size());

        // Revert new resource
        ownerBranchClient.revertResource("/content/new_file2.txt");
        // Assert clean state
        branch = ownerBranchClient.getBranchStatus();
        assertEquals(Protocol.BranchStatus.State.CLEAN, branch.getBranchState());

        // Create a new resource, revert later
        ownerBranchClient.putResourceData("/content/new_file.txt", "new file data".getBytes());

        // Check that branch is dirty
        branch = ownerBranchClient.getBranchStatus();
        assertEquals(Protocol.BranchStatus.State.DIRTY, branch.getBranchState());

        // Rename file
        ownerBranchClient.renameResource("/content", "/content2");
        // Check that branch is dirty
        branch = ownerBranchClient.getBranchStatus();
        assertEquals(Protocol.BranchStatus.State.DIRTY, branch.getBranchState());
        // 4 files under /content
        assertEquals(4, ownerBranchClient.getBranchStatus().getFileStatusList().size());

        // Rename back
        ownerBranchClient.renameResource("/content2", "/content");
        // Revert new resource
        ownerBranchClient.revertResource("/content/new_file.txt");
        // Assert clean state
        branch = ownerBranchClient.getBranchStatus();
        for (Status s : ownerBranchClient.getBranchStatus().getFileStatusList()) {
            System.out.println(String.format("%s %s", s.getIndexStatus(), s.getName()));
        }
        assertEquals(Protocol.BranchStatus.State.CLEAN, branch.getBranchState());
    }

    @Test
    public void updateBranch() throws IOException, RepositoryException {
        // Create branch
        ownerProjectClient.createBranch("branch1");

        byte[] old_data = ownerBranchClient.getResourceData("/content/file1.txt", "");
        assertTrue(new String(old_data).indexOf("testing") == -1);

        // Add commit
        execCommand("scripts/add_testdata_proj1_commit.sh", Long.toString(proj1.getId()));

        // Update branch
        BranchStatus branch = ownerBranchClient.update();
        assertEquals(Protocol.BranchStatus.State.CLEAN, branch.getBranchState());

        // Check clean
        branch = ownerBranchClient.getBranchStatus();
        assertEquals(Protocol.BranchStatus.State.CLEAN, branch.getBranchState());

        byte[] new_data = ownerBranchClient.getResourceData("/content/file1.txt", "");
        assertTrue(new String(new_data).indexOf("testing") != -1);
    }

    @Test
    public void updateBranchMergeResolveYours() throws IOException, RepositoryException {
        // Create branch
        ownerProjectClient.createBranch("branch1");

        // Update resource
        ownerBranchClient.putResourceData("/content/file1.txt", "new file1 data".getBytes());

        // Add commit in main branch
        execCommand("scripts/add_testdata_proj1_commit.sh", Long.toString(proj1.getId()));

        // Commit in this branch
        ownerBranchClient.commit("test message");

        // Update branch
        BranchStatus branch = ownerBranchClient.update();
        assertEquals(Protocol.BranchStatus.State.MERGE, branch.getBranchState());
        assertEquals("/content/file1.txt", branch.getFileStatus(0).getName());
        assertEquals("U", branch.getFileStatus(0).getIndexStatus());

        // Check merge state
        branch = ownerBranchClient.getBranchStatus();
        assertEquals(Protocol.BranchStatus.State.MERGE, branch.getBranchState());

        byte[] new_data = ownerBranchClient.getResourceData("/content/file1.txt", "");
        assertTrue(new String(new_data).indexOf("<<<<<<< HEAD") != -1);

        ownerBranchClient.resolve("/content/file1.txt", "yours");

        byte[] data = ownerBranchClient.getResourceData("/content/file1.txt", "");
        assertTrue(new String(data).indexOf("<<<<<<< HEAD") == -1);

        // Commit in this branch
        ownerBranchClient.commitMerge("test message");

        // Publish this branch
        ownerBranchClient.publish();

        // Make changes visible (in file system)
        execCommand("scripts/reset_testdata_proj1.sh", Long.toString(proj1.getId()));

        String file1 = FileUtil.readEntireFile(new File(String.format("tmp/test_data/%d/content/file1.txt", proj1.getId())));
        assertTrue(file1.indexOf("new file1 data") != -1);
    }

    @Test
    public void updateBranchMergeResolveTheirs() throws IOException, RepositoryException {
        // Create branch
        ownerProjectClient.createBranch("branch1");

        // Update resource
        ownerBranchClient.putResourceData("/content/file1.txt", "new file1 data".getBytes());

        // Add commit in main branch
        execCommand("scripts/add_testdata_proj1_commit.sh", Long.toString(proj1.getId()));

        // Commit in this branch
        ownerBranchClient.commit("test message");

        // Update branch
        BranchStatus branch = ownerBranchClient.update();
        assertEquals(Protocol.BranchStatus.State.MERGE, branch.getBranchState());
        assertEquals("/content/file1.txt", branch.getFileStatus(0).getName());
        assertEquals("U", branch.getFileStatus(0).getIndexStatus());

        // Check merge state
        branch = ownerBranchClient.getBranchStatus();
        assertEquals(Protocol.BranchStatus.State.MERGE, branch.getBranchState());

        byte[] new_data = ownerBranchClient.getResourceData("/content/file1.txt", "");
        assertTrue(new String(new_data).indexOf("<<<<<<< HEAD") != -1);

        ownerBranchClient.resolve("/content/file1.txt", "theirs");

        byte[] data = ownerBranchClient.getResourceData("/content/file1.txt", "");
        assertTrue(new String(data).indexOf("<<<<<<< HEAD") == -1);

        // Commit in this branch
        ownerBranchClient.commitMerge("test message");

        // Publish this branch
        ownerBranchClient.publish();

        // Make changes visible (in file system)
        execCommand("scripts/reset_testdata_proj1.sh", Long.toString(proj1.getId()));

        String file1 = FileUtil.readEntireFile(new File(String.format("tmp/test_data/%d/content/file1.txt", proj1.getId())));
        assertTrue(file1.indexOf("new file1 data") == -1);
    }

    @Test
    public void publishUnmerged() throws IOException, RepositoryException {
        // Create branch
        ownerProjectClient.createBranch("branch1");

        // Update resource
        ownerBranchClient.putResourceData("/content/file1.txt", "new file1 data".getBytes());

        // Add commit in main branch
        execCommand("scripts/add_testdata_proj1_commit.sh", Long.toString(proj1.getId()));

        // Commit in this branch
        ownerBranchClient.commit("my commit message");

        // Update branch
        BranchStatus branch = ownerBranchClient.update();
        assertEquals(Protocol.BranchStatus.State.MERGE, branch.getBranchState());

        // Check merge state
        branch = ownerBranchClient.getBranchStatus();
        assertEquals(Protocol.BranchStatus.State.MERGE, branch.getBranchState());

        byte[] new_data = ownerBranchClient.getResourceData("/content/file1.txt", "");
        assertTrue(new String(new_data).indexOf("<<<<<<< HEAD") != -1);

        // Publish this branch
        try {
            ownerBranchClient.publish();
        }
        catch (RepositoryException e) {
            assertEquals(400, e.getStatusCode());
        }
    }

    @Test
    public void reset() throws IOException, RepositoryException {
        // Create branch
        ownerProjectClient.createBranch("branch1");

        // Check log
        Log log = ownerBranchClient.log(5);
        assertEquals(1, log.getCommitsCount());
        String target = log.getCommits(0).getId();

        // Update resource
        ownerBranchClient.putResourceData("/content/file1.txt", "new file1 data".getBytes());

        // Commit in this branch
        ownerBranchClient.commit("my commit message");

        // Check log
        log = ownerBranchClient.log(5);
        assertEquals(2, log.getCommitsCount());

        ownerBranchClient.reset("hard", target);

        // Check log
        log = ownerBranchClient.log(5);
        assertEquals(1, log.getCommitsCount());
    }

}
