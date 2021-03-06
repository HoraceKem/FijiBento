/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.alignment;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.io.FileWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;

import mpicbg.models.AbstractModel;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.Transforms;
import mpicbg.trakem2.transform.AffineModel2D;
import mpicbg.trakem2.transform.HomographyModel2D;
import mpicbg.trakem2.transform.RigidModel2D;
import mpicbg.trakem2.transform.SimilarityModel2D;
import mpicbg.trakem2.transform.TranslationModel2D;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

/** 
 * @author Seymour Knowles-Barley
 */
public class OptimizeSeriesTransform
{
	@Parameters
	static private class Params
	{
		@Parameter( names = "--help", description = "Display this note", help = true )
        private final boolean help = false;

        @Parameter( names = "--inputfile", description = "Correspondence list file", required = true )
        private String inputfile;
                        
        @Parameter( names = "--tilespecfile", description = "Tilespec file containing all tiles for this montage and current transforms", required = false )
        private String tilespecfile = null;
        
        @Parameter( names = "--modelIndex", description = "Model Index: 0=Translation, 1=Rigid, 2=Similarity, 3=Affine, 4=Homography", required = false )
        private int modelIndex = 3;
        
        @Parameter( names = "--regularizerIndex", description = "Regularizer Index: 0=Translation, 1=Rigid, 2=Similarity, 3=Affine, 4=Homography", required = false )
        private int regularizerIndex = 1;
        
        @Parameter( names = "--regularize", description = "Use regularizer", required = false )
        private boolean regularize = false;        
                        
        @Parameter( names = "--lambda", description = "Regularizer lambda", required = false )
        private float lambda = 0.1f;        
                        
        @Parameter( names = "--maxEpsilon", description = "Max epsilon", required = false )
        private float maxEpsilon = 100.0f;
                        
        @Parameter( names = "--minInlierRatio", description = "Min inlier ratio", required = false )
        private float minInlierRatio = 0.0f;
                        
        @Parameter( names = "--minNumInliers", description = "Min number of inliers", required = false )
        private int minNumInliers = 12;
                                
        @Parameter( names = "--pointMatchScale", description = "Point match scale factor", required = false )
        private double pointMatchScale = 1.0;
        
        @Parameter( names = "--rejectIdentity", description = "Reject identity transform solutions (ignore constant background)", required = false )
        private boolean rejectIdentity = false;
        
        @Parameter( names = "--identityTolerance", description = "Identity transform rejection tolerance", required = false )
        private float identityTolerance = 0.5f;
        
        @Parameter( names = "--multipleHypotheses", description = "Return multiple hypotheses", required = false )
        private boolean multipleHypotheses = false;
        
        @Parameter( names = "--maxNumFailures", description = "Max number of consecutive layer-to-layer match failures", required = false )
        private int maxNumFailures = 3;
        
        @Parameter( names = "--maxIterationsOptimize", description = "Max iterations for optimization", required = false )
        private int maxIterationsOptimize = 1000;
        
        @Parameter( names = "--maxPlateauwidthOptimize", description = "Max plateau width for optimization", required = false )
        private int maxPlateauwidthOptimize = 200;

        @Parameter( names = "--targetPath", description = "Path for the output correspondences", required = true )
        public String targetPath;
        
        @Parameter( names = "--threads", description = "Number of threads to be used", required = false )
        public int numThreads = Runtime.getRuntime().availableProcessors();
        
	}
	
	private OptimizeSeriesTransform() {}
	
	public static void main( final String[] args )
	{
		
		final Params params = new Params();
		
		try
        {
			final JCommander jc = new JCommander( params, args );
        	if ( params.help )
            {
        		jc.usage();
                return;
            }
        }
        catch ( final Exception e )
        {
        	e.printStackTrace();
            final JCommander jc = new JCommander( params );
        	jc.setProgramName( "java [-options] -cp render.jar org.janelia.alignment.RenderTile" );
        	jc.usage(); 
        	return;
        }
		
		final CorrespondenceSpec[] corr_data;
		try
		{
			final Gson gson = new Gson();
			URL url = new URL( params.inputfile );
			corr_data = gson.fromJson( new InputStreamReader( url.openStream() ), CorrespondenceSpec[].class );
		}
		catch ( final MalformedURLException e )
		{
			System.err.println( "URL malformed." );
			e.printStackTrace( System.err );
			return;
		}
		catch ( final JsonSyntaxException e )
		{
			System.err.println( "JSON syntax malformed." );
			e.printStackTrace( System.err );
			return;
		}
		catch ( final Exception e )
		{
			e.printStackTrace( System.err );
			return;
		}

		// The mipmap level to work on
		// TODO: Should be a parameter from the user,
		//       and decide whether or not to create the mipmaps if they are missing
		int mipmapLevel = 0;
		
		
		/* read all tilespecs */
		final HashMap< String, TileSpec > tileSpecMap = new HashMap< String, TileSpec >();

		if (params.tilespecfile != null)
		{
			final URL url;
			final TileSpec[] tileSpecs;
			
			try
			{
				final Gson gson = new Gson();
				url = new URL( params.tilespecfile );
				tileSpecs = gson.fromJson( new InputStreamReader( url.openStream() ), TileSpec[].class );
			}
			catch ( final MalformedURLException e )
			{
				System.err.println( "URL malformed." );
				e.printStackTrace( System.err );
				return;
			}
			catch ( final JsonSyntaxException e )
			{
				System.err.println( "JSON syntax malformed." );
				e.printStackTrace( System.err );
				return;
			}
			catch ( final Exception e )
			{
				e.printStackTrace( System.err );
				return;
			}
			
			for (TileSpec ts : tileSpecs)
			{
				String imageUrl = ts.getMipmapLevels().get("" + mipmapLevel).imageUrl;
				tileSpecMap.put(imageUrl, ts);
			}
		}
		
		
		/* create tiles and models for all layers */
		final HashMap< String, Tile< ? > > tileMap = new HashMap< String, Tile< ? > >();
		
		for ( int i = 0; i < corr_data.length; ++i )
		{
			if (!tileMap.containsKey(corr_data[i].url1))
			{
				if ( params.regularize )
					tileMap.put(corr_data[i].url1, Utils.createInterpolatedAffineTile( params.modelIndex, params.regularizerIndex, params.lambda ) );
				else
					tileMap.put(corr_data[i].url1, Utils.createTile( params.modelIndex ) );
			}
			if (!tileMap.containsKey(corr_data[i].url2))
			{
				if ( params.regularize )
					tileMap.put(corr_data[i].url2, Utils.createInterpolatedAffineTile( params.modelIndex, params.regularizerIndex, params.lambda ) );
				else
					tileMap.put(corr_data[i].url2, Utils.createTile( params.modelIndex ) );
			}
		}
		
		/* collect all pairs of slices for which a model could be found */
		final ArrayList< Triple< String, String, Collection< PointMatch> > > pairs = new ArrayList< Triple< String, String, Collection< PointMatch > > >();
		
		/* match and filter feature correspondences */
		int numFailures = 0;
		
J:		for ( int i = 0; i < corr_data.length; )
		{
			final ArrayList< Thread > threads = new ArrayList< Thread >( params.numThreads );
			
			final int numThreads = Math.min( params.numThreads, corr_data.length - i );
			
			final ArrayList< Triple< String, String, Collection< PointMatch > > > models =
				new ArrayList< Triple< String, String, Collection< PointMatch > > >( numThreads );

			for ( int k = 0; k < numThreads; ++k )
				models.add( null );

			for ( int t = 0;  t < numThreads && i < corr_data.length; ++t, ++i )
			{
				final int ti = t;
				final CorrespondenceSpec corr = corr_data[i];
				final String layerNameA = corr.url1;
				final String layerNameB = corr.url2;

				final Thread thread = new Thread()
				{
					@Override
					public void run()
					{
						ArrayList< PointMatch > candidates = new ArrayList< PointMatch >();
						
						candidates.addAll(corr.correspondencePointPairs);

						/* scale the candidates */
						for ( final PointMatch pm : candidates )
						{
							final Point p1 = pm.getP1();
							final Point p2 = pm.getP2();
							final float[] l1 = p1.getL();
							final float[] w1 = p1.getW();
							final float[] l2 = p2.getL();
							final float[] w2 = p2.getW();
				
							l1[ 0 ] *= params.pointMatchScale;
							l1[ 1 ] *= params.pointMatchScale;
							w1[ 0 ] *= params.pointMatchScale;
							w1[ 1 ] *= params.pointMatchScale;
							l2[ 0 ] *= params.pointMatchScale;
							l2[ 1 ] *= params.pointMatchScale;
							w2[ 0 ] *= params.pointMatchScale;
							w2[ 1 ] *= params.pointMatchScale;
				
						}
						
						AbstractModel< ? > model = Utils.createModel( params.modelIndex );
				
						final ArrayList< PointMatch > inliers = new ArrayList< PointMatch >();
				
						boolean again = false;
						int nHypotheses = 0;
						try
						{
							do
							{
								again = false;
								final ArrayList< PointMatch > inliers2 = new ArrayList< PointMatch >();
								final boolean modelFound = model.filterRansac(
											candidates,
											inliers2,
											1000,
											params.maxEpsilon,
											params.minInlierRatio,
											params.minNumInliers,
											3 );
								if ( modelFound )
								{
									candidates.removeAll( inliers2 );
				
									if ( params.rejectIdentity )
									{
										final ArrayList< Point > points = new ArrayList< Point >();
										PointMatch.sourcePoints( inliers2, points );
										if ( Transforms.isIdentity( model, points, params.identityTolerance ) )
										{
											System.out.println( "Identity transform for " + inliers2.size() + " matches rejected." );
											again = true;
										}
										else
										{
											++nHypotheses;
											inliers.addAll( inliers2 );
											again = params.multipleHypotheses;
										}
									}
									else
									{
										++nHypotheses;
										inliers.addAll( inliers2 );
										again = params.multipleHypotheses;
									}
								}
							}
							while ( again );
						}
						catch ( final NotEnoughDataPointsException e ) {}
				
						if ( nHypotheses > 0 && params.multipleHypotheses )
						{
							try
							{
									model.fit( inliers );
									PointMatch.apply( inliers, model );
							}
							catch ( final NotEnoughDataPointsException e ) {}
							catch ( final IllDefinedDataPointsException e )
							{
								nHypotheses = 0;
							}
						}
				
						if ( nHypotheses > 0 )
						{								
							System.out.println( layerNameA + " -> " + layerNameB + ": " + inliers.size() + " corresponding features with an average displacement of " + ( PointMatch.meanDistance( inliers ) ) + "px identified." );
							System.out.println( "Estimated transformation model: " + model + ( params.multipleHypotheses ? ( " from " + nHypotheses + " hypotheses" ) : "" ) );
							models.set( ti, new Triple< String, String, Collection< PointMatch > >( layerNameA, layerNameB, inliers ) );
						}
						else
						{
							System.out.println( layerNameA + " -> " + layerNameB + ": no correspondences found." );
							return;
						}
					}

				};
				threads.add( thread );
				thread.start();
			}

			try
			{
				for ( final Thread thread : threads )
					thread.join();
			}
			catch ( final InterruptedException e )
			{
				System.out.println( "Establishing feature correspondences interrupted." );
				for ( final Thread thread : threads )
					thread.interrupt();
				try
				{
					for ( final Thread thread : threads )
						thread.join();
				}
				catch ( final InterruptedException f ) {}
				return;
			}

			threads.clear();

			/* collect successfully matches pairs and break the search on gaps */
			for ( int t = 0; t < models.size(); ++t )
			{
				final Triple< String, String, Collection< PointMatch > > pair = models.get( t );
				if ( pair == null )
				{
					if ( ++numFailures > params.maxNumFailures )
						break J;
				}
				else
				{
					numFailures = 0;
					pairs.add( pair );
				}
			}
		}
			
		/* Optimization */
		final TileConfiguration tileConfiguration = new TileConfiguration();

		for ( final Triple< String, String, Collection< PointMatch > > pair : pairs )
		{
			final Tile< ? > t1 = tileMap.get( pair.a );
			final Tile< ? > t2 = tileMap.get( pair.b );

			tileConfiguration.addTile( t1 );
			tileConfiguration.addTile( t2 );
			t2.connect( t1, pair.c );
		}

//		for ( int i = 0; i < layerRange.size(); ++i )
//		{
//			final Layer layer = layerRange.get( i );
//			if ( fixedLayers.contains( layer ) )
//				tileConfiguration.fixTile( tileMap.get( i ) );
//		}

		try
		{
			final List< Tile< ? >  > nonPreAlignedTiles = tileConfiguration.preAlign();
	
			System.out.println( "pre-aligned all but " + nonPreAlignedTiles.size() + " tiles" );
	
			tileConfiguration.optimize(
					params.maxEpsilon,
					params.maxIterationsOptimize,
					params.maxPlateauwidthOptimize );
	
			System.out.println( new StringBuffer( "Successfully optimized configuration of " ).append( tileMap.size() ).append( " tiles:" ).toString() );
			System.out.println( "  average displacement: " + String.format( "%.3f", tileConfiguration.getError() ) + "px" );
			System.out.println( "  minimal displacement: " + String.format( "%.3f", tileConfiguration.getMinError() ) + "px" );
			System.out.println( "  maximal displacement: " + String.format( "%.3f", tileConfiguration.getMaxError() ) + "px" );
		}
		catch ( final Exception e )
		{
			System.err.println( "Error optimizing:" );
			e.printStackTrace( System.err );
		}


//		if ( propagateTransformBefore || propagateTransformAfter )
//		{
//			final Layer first = layerRange.get( 0 );
//			final List< Layer > layers = first.getParent().getLayers();
//			if ( propagateTransformBefore )
//			{
//				final AffineTransform b = translateAffine( box, ( ( Affine2D< ? > )tileMap.get( 0 ).getModel() ).createAffine() );
//				final int firstLayerIndex = first.getParent().getLayerIndex( first.getId() );
//				for ( int i = 0; i < firstLayerIndex; ++i )
//					applyTransformToLayer( layers.get( i ), b, filter );
//			}
//			if ( propagateTransformAfter )
//			{
//				final Layer last = layerRange.get( layerRange.size() - 1 );
//				final AffineTransform b = translateAffine( box, ( ( Affine2D< ? > )tileMap.get( tileMap.size() - 1 ).getModel() ).createAffine() );
//				final int lastLayerIndex = last.getParent().getLayerIndex( last.getId() );
//				for ( int i = lastLayerIndex + 1; i < layers.size(); ++i )
//					applyTransformToLayer( layers.get( i ), b, filter );
//			}
//		}
			
		System.out.println( "Optimization complete. Generating tile transforms.");
		
		ArrayList< TileSpec > out_tiles = new ArrayList< TileSpec >();
		
		// Export new transforms, TODO: append to existing tilespec files
		for(Entry<String, Tile< ? > > entry : tileMap.entrySet()) {
		    String tileUrl = entry.getKey();
		    Tile< ? > tileValue = entry.getValue();
		    
		    TileSpec ts = tileSpecMap.get(tileUrl);
		    if (ts == null)
		    {
		    	System.out.println("Generating new tilespec for image " + tileUrl + ".");
		    	ts = new TileSpec();
		    	ts.setMipmapLevelImageUrl("" + mipmapLevel, tileUrl);
		    }
		    
		    @SuppressWarnings("rawtypes")
			Model genericModel = tileValue.getModel();
		    
		    Transform addedTransform = new Transform();
		    addedTransform.className = genericModel.getClass().getCanonicalName();
		    
			switch ( params.modelIndex )
			{
			case 0:
				addedTransform.dataString = ((TranslationModel2D) genericModel).toDataString();
				break;
			case 1:
				addedTransform.dataString = ((RigidModel2D) genericModel).toDataString();
				break;
			case 2:
				addedTransform.dataString = ((SimilarityModel2D) genericModel).toDataString();
				break;
			case 3:
				addedTransform.dataString = ((AffineModel2D) genericModel).toDataString();
				break;
			case 4:
				addedTransform.dataString = ((HomographyModel2D) genericModel).toDataString();
				break;
			default:
				addedTransform.dataString = genericModel.toString();
			}		    
		    
			//Apply to the corresponding tilespec transforms
			ArrayList< Transform > outTransforms = new ArrayList< Transform >(Arrays.asList(ts.transforms));
			outTransforms.add(addedTransform);
			ts.transforms = outTransforms.toArray(ts.transforms);
		    
		    out_tiles.add(ts);
		}
		
		System.out.println( "Exporting tiles.");
		
		try {
			Writer writer = new FileWriter(params.targetPath);
	        //Gson gson = new GsonBuilder().create();
	        Gson gson = new GsonBuilder().setPrettyPrinting().create();
	        gson.toJson(out_tiles, writer);
	        writer.close();
	    }
		catch ( final IOException e )
		{
			System.err.println( "Error writing JSON file: " + params.targetPath );
			e.printStackTrace( System.err );
		}

		System.out.println( "Done." );
	}
	
}
