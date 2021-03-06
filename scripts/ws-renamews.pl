#!/usr/bin/env perl
########################################################################
# adpated for WS 0.1.0+ by Michael Sneddon, LBL
# Original authors: Christopher Henry, Scott Devoid, Paul Frybarger
# Contact email: mwsneddon@lbl.gov or chenry@mcs.anl.gov
########################################################################
use strict;
use warnings;
use Getopt::Long::Descriptive;
use Text::Table;
use Bio::KBase::workspace::ScriptHelpers qw(get_ws_client workspace);

my $serv = get_ws_client();
#Defining globals describing behavior
my $primaryArgs = ["Original Workspace ID or Name","New Name"];
my $servercommand = "rename_workspace";
my $translation = {};
#Defining usage and options
my ($opt, $usage) = describe_options(
    'ws-renamews <'.join("> <",@{$primaryArgs}).'> %o',
    [ 'showerror|e', 'Show full stack trace of any errors in execution',{"default"=>0}],
    [ 'help|h|?', 'Print this usage information' ]
);
$usage = "\nNAME\n  ws-renamews -- rename a workspace\n\nSYNOPSIS\n  ".$usage;
$usage .= "\n";
if (defined($opt->{help})) {
	print $usage;
	exit 0;
}
#Processing primary arguments
if (scalar(@ARGV) > scalar(@{$primaryArgs})) {
	print STDERR "Too many input arguments given.  Run with -h or --help for usage information\n";
	exit 1;
}
foreach my $arg (@{$primaryArgs}) {
	$opt->{$arg} = shift @ARGV;
	if (!defined($opt->{$arg})) {
		print STDERR "Not enough input arguments provided.  Run with -h or --help for usage information\n";
		exit 1;
	}
}

#Instantiating parameters
my $wsIdOrName = $opt->{"Original Workspace ID or Name"};
my $wsi = {};
if ($wsIdOrName =~ /^\d+$/) { $wsi->{"id"} = $wsIdOrName+0; }
else { $wsi->{"workspace"} = $wsIdOrName.""; }

my $params = {
	wsi => $wsi,
	new_name => $opt->{"New Name"}
};

#Calling the server
my $output;
if ($opt->{showerror} == 0){
	eval { $output = $serv->$servercommand($params); };
	if($@) {
		print "Workspace could not be renamed!\n";
		print STDERR $@->{message}."\n";
		if(defined($@->{status_line})) {print STDERR $@->{status_line}."\n" };
		print STDERR "\n";
		exit 1;
	}
} else {
	$output = $serv->$servercommand($params);
}

print "Workspace renamed successfully.\n";

exit 0;