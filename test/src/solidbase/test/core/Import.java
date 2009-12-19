/*--
 * Copyright 2006 Ren� M. de Bloois
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package solidbase.test.core;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

import org.testng.annotations.Test;

import solidbase.core.Database;
import solidbase.core.TestUtil;
import solidbase.core.Patcher;

public class Import
{
	@Test
	public void testImport() throws IOException, SQLException
	{
		Patcher.end();

		Patcher.setCallBack( new TestProgressListener() );
		Database database = new Database( "org.hsqldb.jdbcDriver", "jdbc:hsqldb:mem:import3", "sa", null );
		Patcher.setDefaultConnection( database );

		Patcher.openPatchFile( "testpatch-import1.sql" );
		try
		{
			Set< String > targets = Patcher.getTargets( false, null );
			assert targets.size() > 0;

			Patcher.patch( "1.0.2" );
		}
		finally
		{
			Patcher.closePatchFile();
		}

		TestUtil.verifyVersion( "1.0.2", null, 4, null );
		TestUtil.assertRecordCount( database, "TEMP", 4 );
	}
}
