/*--
 * Copyright 2011 Ren� M. de Bloois
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

package solidbase.core.plugins;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import solidbase.core.Command;
import solidbase.core.CommandFileException;
import solidbase.core.CommandListener;
import solidbase.core.CommandProcessor;
import solidbase.core.SystemException;
import solidbase.util.CSVWriter;
import solidbase.util.JDBCSupport;
import solidbase.util.SQLTokenizer;
import solidbase.util.SQLTokenizer.Token;
import solidstack.io.Resource;
import solidstack.io.Resources;
import solidstack.io.SourceReaders;


/**
 * This plugin executes EXPORT CSV statements.
 *
 * @author Ren� M. de Bloois
 * @since Aug 12, 2011
 */
// TODO To compressed file
// TODO Escape with \ instead of doubling double quotes. This means also \n \t \r. ESCAPE DQ CR LF TAB WITH \
public class ExportCSV implements CommandListener
{
	static private final Pattern triggerPattern = Pattern.compile( "\\s*EXPORT\\s+CSV\\s+.*", Pattern.DOTALL | Pattern.CASE_INSENSITIVE );


	//@Override
	public boolean execute( CommandProcessor processor, Command command, boolean skip ) throws SQLException
	{
		if( command.isTransient() )
			return false;

		if( !triggerPattern.matcher( command.getCommand() ).matches() )
			return false;

		if( skip )
			return true;

		Parsed parsed = parse( command );

		Resource csvResource = Resources.getResource( parsed.fileName ); // Relative to current folder

		try
		{
			OutputStream out = csvResource.getOutputStream();
			if( parsed.gzip )
				out = new BufferedOutputStream( new GZIPOutputStream( out, 65536 ), 65536 ); // TODO Ctrl-C, close the outputstream?

			CSVWriter csvWriter;
			try
			{
				csvWriter = new CSVWriter( new OutputStreamWriter( out, parsed.encoding ), parsed.separator, false );
			}
			catch( UnsupportedEncodingException e )
			{
				// toString() instead of getMessage(), the getMessage only returns the encoding string
				throw new CommandFileException( e.toString(), command.getLocation() );
			}

			try
			{
				Statement statement = processor.createStatement();
				try
				{
					ResultSet result = statement.executeQuery( parsed.query );

					ResultSetMetaData metaData = result.getMetaData();
					int columns = metaData.getColumnCount();
					int[] types = new int[ columns ];
					String[] names = new String[ columns ];
					boolean[] coalesce = new boolean[ columns ];
					int firstCoalesce = -1;

					for( int i = 0; i < columns; i++ )
					{
						String name = metaData.getColumnName( i + 1 ).toUpperCase();
						types[ i ] = metaData.getColumnType( i + 1 );
						names[ i ] = name;
						if( parsed.coalesce != null && parsed.coalesce.contains( name ) )
						{
							coalesce[ i ] = true;
							if( firstCoalesce < 0 )
								firstCoalesce = i;
						}
					}

					if( parsed.withHeader )
					{
						for( int i = 0; i < columns; i++ )
							if( !coalesce[ i ] || firstCoalesce == i )
								csvWriter.writeValue( names[ i ] );
						csvWriter.nextRecord();
					}

					int count = 0;
					int progressNext = 1;
					while( result.next() )
					{
						Object coalescedValue = null;
						if( parsed.coalesce != null )
							for( int i = 0; i < columns; i++ )
								if( coalesce[ i ] )
								{
									coalescedValue = JDBCSupport.getValue( result, types, i );
									if( coalescedValue != null )
										break;
								}

						for( int i = 0; i < columns; i++ )
						{
							if( !coalesce[ i ] || firstCoalesce == i )
							{
								Object value = coalescedValue;
								if( firstCoalesce != i )
									value = JDBCSupport.getValue( result, types, i );

								// TODO Write null as ^NULL in extended format?
								if( value == null )
								{
									csvWriter.writeValue( (String)null );
									continue;
								}

								if( value instanceof Clob )
								{
									Reader in = ( (Clob)value ).getCharacterStream();
									csvWriter.writeValue( in );
									in.close();
								}
								else if( value instanceof Blob )
								{
									InputStream in = ( (Blob)value ).getBinaryStream();
									csvWriter.writeValue( in );
									in.close();
								}
								else if( value instanceof byte[] )
								{
									csvWriter.writeValue( (byte[])value );
								}
								else
									csvWriter.writeValue( value.toString() );
							}
						}

						csvWriter.nextRecord();

						count++;
						if( count >= progressNext )
						{
							progressNext = count + count / 10;
							processor.getProgressListener().println( "Written " + count + " records..." );
						}
//						if( count % 100000 == 0 )

						processor.getProgressListener().println( "Written " + count + " records." );
					}
				}
				finally
				{
					processor.closeStatement( statement, true );
				}
			}
			finally
			{
				csvWriter.close();
			}
		}
		catch( IOException e )
		{
			throw new SystemException( e );
		}

		return true;
	}


	/**
	 * Parses the given command.
	 *
	 * @param command The command to be parsed.
	 * @return A structure representing the parsed command.
	 */
	static protected Parsed parse( Command command )
	{
		Parsed result = new Parsed();

		SQLTokenizer tokenizer = new SQLTokenizer( SourceReaders.forString( command.getCommand(), command.getLocation() ) );

		tokenizer.get( "EXPORT" );
		tokenizer.get( "CSV" );

		Token t = tokenizer.get( "WITH", "SEPARATED", "COALESCE", "FILE" );
		if( t.eq( "WITH" ) )
		{
			tokenizer.get( "HEADER" );
			result.withHeader = true;

			t = tokenizer.get( "SEPARATED", "COALESCE", "FILE" );
		}

		if( t.eq( "SEPARATED" ) )
		{
			tokenizer.get( "BY" );
			t = tokenizer.get();
			if( t.eq( "TAB" ) )
				result.separator = '\t';
			else if( t.eq( "SPACE" ) )
				result.separator = ' ';
			else
			{
				if( t.length() != 1 )
					throw new CommandFileException( "Expecting [TAB], [SPACE] or a single character, not [" + t + "]", tokenizer.getLocation() );
				result.separator = t.getValue().charAt( 0 );
			}

			t = tokenizer.get( "COALESCE", "FILE" );
		}

		if( t.eq( "COALESCE" ) )
		{
			result.coalesce = new HashSet< String >();
			do
			{
				t = tokenizer.get();
				result.coalesce.add( t.getValue().toUpperCase() );
				t = tokenizer.get();
			}
			while( t.eq( "," ) );
			tokenizer.push( t );

			tokenizer.get( "FILE" );
		}

		t = tokenizer.get();
		String file = t.getValue();
		if( !file.startsWith( "\"" ) )
			throw new CommandFileException( "Expecting filename enclosed in double quotes, not [" + t + "]", tokenizer.getLocation() );
		file = file.substring( 1, file.length() - 1 );

		t = tokenizer.get( "ENCODING" );
		t = tokenizer.get();
		String encoding = t.getValue();
		if( !encoding.startsWith( "\"" ) )
			throw new CommandFileException( "Expecting encoding enclosed in double quotes, not [" + t + "]", tokenizer.getLocation() );
		encoding = encoding.substring( 1, encoding.length() - 1 );

		t = tokenizer.get();
		if( t.eq( "GZIP" ) )
			result.gzip = true;
		else
			tokenizer.push( t );

		String query = tokenizer.getRemaining();

		result.fileName = file;
		result.encoding = encoding;
		result.query = query;

		return result;
	}


	//@Override
	public void terminate()
	{
		// Nothing to clean up
	}


	/**
	 * A parsed command.
	 *
	 * @author Ren� M. de Bloois
	 */
	static protected class Parsed
	{
		protected boolean withHeader;

		/** The separator. */
		protected char separator = ',';

		/** The file path to export to */
		protected String fileName;

		/** The encoding of the file */
		protected String encoding;

		protected boolean gzip;

		/** The query */
		protected String query;

		/** Which columns need to be coalesced */
		protected Set< String > coalesce;
	}
}
